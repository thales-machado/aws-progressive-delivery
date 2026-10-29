package progressivedelivery

class Orchestrator implements Serializable {

    static final String MARKER = 'aws-progressive-delivery:'

    private final Pipeline pipeline
    private final Gateway gateway
    private final Monitor monitor
    private final Map cfg
    private final List summary = []

    Orchestrator(Pipeline pipeline, Gateway gateway, Monitor monitor, Map cfg) {
        this.pipeline = pipeline
        this.gateway = gateway
        this.monitor = monitor
        this.cfg = cfg
    }

    void run() {
        List targets = resolveTargets()
        try {
            switch (cfg.action) {
                case 'START':
                case 'CONTINUE':
                    release(targets, cfg.action == 'CONTINUE')
                    break
                case 'PROMOTE':
                    promoteExisting(targets)
                    break
                case 'ROLLBACK':
                    rollbackExisting(targets)
                    break
                case 'STATUS':
                    status(targets)
                    break
                case 'CLEANUP':
                    cleanup(targets)
                    break
            }
        } finally {
            writeSummary()
        }
    }

    private List resolveTargets() {
        List targets = []
        for (Object entry : cfg.targets) {
            Map target = new LinkedHashMap(entry as Map)
            target.restApiId = gateway.restApiId(target.name as String)
            target.promoted = false
            target.monitorUp = false
            target.canaryId = null
            targets << target
        }
        return targets
    }

    private void release(List targets, boolean resume) {
        if (cfg.mode == 'sequential') {
            runSequential(targets, resume)
        } else {
            releaseGroup(targets, resume)
        }
    }

    private void runSequential(List targets, boolean resume) {
        List promotedThisRun = []
        boolean anyCanary = false
        for (Object entry : targets) {
            Map target = entry as Map
            if (resume && gateway.getStage(target.restApiId, target.stage).canarySettings == null) {
                pipeline.log("${target.label}: no canary, skipping")
                continue
            }
            anyCanary = true
            String outcome
            try {
                outcome = releaseGroup([target], resume)
            } catch (Exception e) {
                revertPeers(promotedThisRun, target)
                throw e
            }
            if (outcome == 'HELD') {
                pipeline.log("Held on ${target.label}; targets after it were not started")
                return
            }
            promotedThisRun << target
        }
        if (resume && !anyCanary) {
            throw new IllegalStateException('CONTINUE found no canary on any target')
        }
    }

    private void revertPeers(List promoted, Map failed) {
        if (promoted.isEmpty()) {
            return
        }
        String names = promoted.collect { it.label }.join(', ')
        String decision = 'REVERT'
        if (cfg.onPeerFailure == 'ask') {
            decision = pipeline.choose("${failed.label} failed. Revert the already promoted targets (${names})?",
                ['REVERT', 'KEEP'], cfg.approvals.timeoutMinutes as int)
        } else if (cfg.onPeerFailure == 'keep') {
            decision = 'KEEP'
        }
        if (decision != 'REVERT') {
            pipeline.log("Keeping promoted targets: ${names}")
            return
        }
        for (Object entry : promoted) {
            rollbackTarget(entry as Map)
        }
    }

    private String releaseGroup(List group, boolean resume) {
        String title = group.collect { it.label }.join(', ')
        pipeline.log("Release ${resume ? 'resume' : 'start'}: ${title}")
        try {
            return releaseSteps(group, resume)
        } catch (ReleaseAborted e) {
            pipeline.log("Aborted: ${e.message}")
            rollbackGroup(group, false)
            throw e
        } catch (Exception e) {
            pipeline.log("Failed: ${e.message}")
            rollbackGroup(group, true)
            throw e
        }
    }

    private String releaseSteps(List group, boolean resume) {
        int currentPercent = 0
        pipeline.runStage('Validate') {
            currentPercent = validate(group, resume)
        }

        List steps = []
        for (Object step : cfg.steps) {
            if ((step as int) > currentPercent) {
                steps << step
            }
        }

        boolean first = !resume
        boolean monitorStarted = false
        for (Object entry : steps) {
            int percent = entry as int
            boolean offerHold = !first
            if (cfg.approvals.perStep) {
                String answer = decide("Shift ${percent}% of traffic to the canary for ${labels(group)}?", 'PROCEED', offerHold ? 'HOLD' : null)
                if (answer == 'HOLD') {
                    return hold(group)
                }
            }
            if (!monitorStarted) {
                pipeline.runStage('Monitor') {
                    forEachTarget(group) { Map t -> startMonitor(t) }
                }
                monitorStarted = true
            }
            if (first) {
                pipeline.runStage("Canary ${percent}%") {
                    forEachTarget(group) { Map t -> createCanary(t, percent) }
                }
            } else {
                pipeline.runStage("Canary ${percent}%") {
                    forEachTarget(group) { Map t -> gateway.setPercent(t.restApiId, t.stage, percent) }
                }
            }
            first = false
            pipeline.runStage("Bake ${percent}%") {
                bake(group, percent)
            }
        }

        if (cfg.approvals.beforePromote) {
            String answer = decide("Promote the canary to 100% for ${labels(group)}?", 'PROMOTE', 'HOLD')
            if (answer == 'HOLD') {
                return hold(group)
            }
        }
        pipeline.runStage('Promote') {
            for (Object t : group) {
                stopMonitor(t as Map)
            }
            forEachTarget(group) { Map t -> promoteTarget(t) }
        }
        return 'PROMOTED'
    }

    private int validate(List group, boolean resume) {
        int percent = 0
        int lowest = 100
        for (Object entry : group) {
            Map t = entry as Map
            Map stage = gateway.getStage(t.restApiId, t.stage)
            Map canary = stage.canarySettings as Map
            t.stableId = stage.deploymentId
            t.overrides = canary?.stageVariableOverrides ?: (cfg.canary.stageVariableOverrides ?: [:])
            t.stableVars = stage.variables ?: [:]
            if (!resume && canary != null) {
                throw new IllegalStateException("${t.label} already has a canary; use CONTINUE, PROMOTE or ROLLBACK")
            }
            if (resume) {
                if (canary == null) {
                    throw new IllegalStateException("${t.label} has no canary to continue")
                }
                String description = gateway.deploymentDescription(t.restApiId, canary.deploymentId as String)
                if (!description.contains(MARKER) && !cfg.adoptForeignCanary) {
                    throw new IllegalStateException("${t.label} has a canary this tool did not create; set adoptForeignCanary to take it over")
                }
                t.canaryId = canary.deploymentId
                t.monitorUp = cfg.monitor.enabled
                lowest = Math.min(lowest, (canary.percentTraffic as double).intValue())
                percent = lowest
            }
            pipeline.log("${t.label}: stable=${t.stableId}${canary ? " canary=${canary.deploymentId} at ${canary.percentTraffic}%" : ''}")
        }
        return percent
    }

    private String decide(String message, String proceed, String hold) {
        List choices = ['ABORT', proceed]
        if (hold != null) {
            choices << hold
        }
        String answer = pipeline.choose(message, choices, cfg.approvals.timeoutMinutes as int)
        if (answer == 'ABORT') {
            throw new ReleaseAborted('aborted at approval or approval timed out')
        }
        return answer
    }

    private String hold(List group) {
        pipeline.log("HOLD on ${labels(group)}: canary and alarms stay in place. Resume with ACTION=CONTINUE, finish with PROMOTE or ROLLBACK.")
        return 'HELD'
    }

    private void createCanary(Map t, int percent) {
        String description = "${MARKER}${pipeline.buildTag() ?: 'manual'}".toString()
        t.canaryId = gateway.createCanary(t.restApiId, t.stage, percent, t.overrides as Map,
            cfg.canary.useStageCache as boolean, description)
        pipeline.log("${t.label}: canary deployment ${t.canaryId} at ${percent}%")
    }

    private void bake(List group, int percent) {
        long start = pipeline.nowEpochSeconds()
        long deadline = start + (cfg.bakeMinutes as long) * 60L
        long hardDeadline = deadline + (cfg.gate.maxExtensionMinutes as long) * 60L
        long poll = cfg.gate.pollSeconds as long
        long delay = cfg.gate.metricDelaySeconds as long

        while (true) {
            long now = pipeline.nowEpochSeconds()
            long wait = deadline > now ? Math.min(poll, deadline - now) : poll
            pipeline.sleepSeconds((int) wait)
            now = pipeline.nowEpochSeconds()

            Map verdict = evaluateGroup(group, start, now - delay)
            if (verdict.status == 'FAIL') {
                throw new GateFailed("Gate failed at ${percent}%: ${verdict.reasons.join('; ')}")
            }
            if (now >= deadline && verdict.status == 'PASS') {
                return
            }
            if (now >= hardDeadline) {
                if (cfg.gate.requireTraffic) {
                    throw new GateFailed("Not enough traffic at ${percent}% to judge the canary: ${verdict.reasons.join('; ')}")
                }
                pipeline.log("Bake at ${percent}% ended without enough traffic; continuing because requireTraffic is false")
                return
            }
        }
    }

    private Map evaluateGroup(List group, long from, long to) {
        String status = 'PASS'
        List reasons = []
        for (Object entry : group) {
            Map t = entry as Map
            String metricStage = cfg.canaryMetricStage ?: "${t.stage}/Canary".toString()
            Map metrics = gateway.metrics(t.apiName as String, metricStage, from, to)
            Map result = HealthGate.evaluate(metrics, cfg.gate as Map)
            pipeline.log("${t.label}: ${result.status} (${result.summary})")
            for (Object reason : result.reasons) {
                reasons << "${t.label}: ${reason}".toString()
            }
            if (result.status == 'FAIL') {
                status = 'FAIL'
            } else if (result.status == 'INSUFFICIENT' && status == 'PASS') {
                status = 'INSUFFICIENT'
            }
        }
        return [status: status, reasons: reasons]
    }

    private void promoteTarget(Map t) {
        gateway.promote(t.restApiId, t.stage, t.canaryId as String, t.overrides as Map)
        t.promoted = true
        pipeline.log("${t.label}: promoted ${t.canaryId}")
        summary << "${t.label}: promoted ${t.canaryId} (previous ${t.stableId})".toString()
    }

    private void rollbackGroup(List group, boolean urgent) {
        if (urgent) {
            for (Object t : group) {
                silenceMonitor(t as Map)
            }
        } else {
            for (Object t : group) {
                stopMonitor(t as Map)
            }
        }
        for (Object t : group) {
            rollbackTarget(t as Map)
        }
        if (urgent) {
            for (Object t : group) {
                stopMonitor(t as Map)
            }
        }
    }

    private void rollbackTarget(Map t) {
        try {
            if (t.promoted) {
                Map variables = [:]
                for (Object key : (t.overrides as Map).keySet()) {
                    variables[key] = (t.stableVars as Map)[key]
                }
                gateway.restoreStable(t.restApiId, t.stage, t.stableId as String, variables)
                t.promoted = false
                pipeline.log("${t.label}: reverted to ${t.stableId}")
                summary << "${t.label}: reverted to ${t.stableId}".toString()
            } else if (t.canaryId != null) {
                gateway.removeCanary(t.restApiId, t.stage)
                String canaryId = t.canaryId
                t.canaryId = null
                pipeline.log("${t.label}: canary removed")
                summary << "${t.label}: canary ${canaryId} rolled back".toString()
                try {
                    gateway.deleteDeployment(t.restApiId, canaryId)
                } catch (Exception e) {
                    pipeline.unstable("${t.label}: could not delete deployment ${canaryId}: ${e.message}")
                }
            }
        } catch (Exception e) {
            pipeline.unstable("${t.label}: rollback failed, check the stage manually: ${e.message}")
        }
    }

    private void promoteExisting(List targets) {
        for (Object entry : targets) {
            Map t = entry as Map
            Map stage = gateway.getStage(t.restApiId, t.stage)
            Map canary = stage.canarySettings as Map
            if (canary == null) {
                throw new IllegalStateException("${t.label} has no canary to promote")
            }
            t.stableId = stage.deploymentId
            t.canaryId = canary.deploymentId
            t.overrides = canary.stageVariableOverrides ?: [:]
            stopMonitor(t)
            promoteTarget(t)
        }
    }

    private void rollbackExisting(List targets) {
        for (Object entry : targets) {
            Map t = entry as Map
            Map canary = gateway.getStage(t.restApiId, t.stage).canarySettings as Map
            stopMonitor(t)
            if (canary == null) {
                pipeline.log("${t.label}: no canary")
                continue
            }
            t.canaryId = canary.deploymentId
            rollbackTarget(t)
        }
    }

    private void cleanup(List targets) {
        for (Object entry : targets) {
            Map t = entry as Map
            t.monitorUp = true
            stopMonitor(t)
        }
    }

    private void status(List targets) {
        for (Object entry : targets) {
            Map t = entry as Map
            Map stage = gateway.getStage(t.restApiId, t.stage)
            Map canary = stage.canarySettings as Map
            String line = "${t.label}: stable=${stage.deploymentId}".toString()
            if (canary != null) {
                long now = pipeline.nowEpochSeconds()
                String metricStage = cfg.canaryMetricStage ?: "${t.stage}/Canary".toString()
                Map metrics = gateway.metrics(t.apiName as String, metricStage, now - 600L, now)
                Map result = HealthGate.evaluate(metrics, cfg.gate as Map)
                line += " canary=${canary.deploymentId} at ${canary.percentTraffic}% last10m=${result.status} (${result.summary})"
            } else {
                line += ' no canary'
            }
            List alarms = gateway.alarmNames("${cfg.monitor.namePrefix}-${t.apiName}-${t.stage}-".toString())
            line += " alarms=${alarms.size()}"
            pipeline.log(line)
            summary << line
        }
    }

    private void startMonitor(Map t) {
        if (!cfg.monitor.enabled) {
            return
        }
        monitor.apply(t)
        t.monitorUp = true
    }

    private void stopMonitor(Map t) {
        if (!t.monitorUp) {
            return
        }
        try {
            monitor.destroy(t)
            t.monitorUp = false
        } catch (Exception e) {
            pipeline.unstable("${t.label}: alarms were not destroyed (${e.message}); notifications silenced, run ACTION=CLEANUP")
            silenceMonitor(t)
            t.monitorUp = false
        }
    }

    private void silenceMonitor(Map t) {
        if (!t.monitorUp) {
            return
        }
        try {
            monitor.silence(t)
        } catch (Exception e) {
            pipeline.unstable("${t.label}: could not silence alarms: ${e.message}")
        }
    }

    private void forEachTarget(List group, Closure op) {
        if (cfg.mode == 'parallel' && group.size() > 1) {
            Map branches = [:]
            for (Object t : group) {
                branches[(t as Map).label] = bind(op, t as Map)
            }
            pipeline.runParallel(branches)
        } else {
            for (Object t : group) {
                op(t as Map)
            }
        }
    }

    private Closure bind(Closure op, Map target) {
        return { -> op(target) }
    }

    private static String labels(List group) {
        return group.collect { it.label }.join(', ')
    }

    private void writeSummary() {
        if (summary.isEmpty()) {
            return
        }
        pipeline.writeText('.progressive-delivery/summary.txt', summary.join('\n') + '\n')
        pipeline.archive('.progressive-delivery/summary.txt')
    }
}
