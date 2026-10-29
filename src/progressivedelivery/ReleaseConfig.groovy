package progressivedelivery

class ReleaseConfig {

    static final List ACTIONS = ['START', 'CONTINUE', 'PROMOTE', 'ROLLBACK', 'STATUS', 'CLEANUP']
    static final List MODES = ['single', 'parallel', 'sequential']

    static Map defaults() {
        return [
            action             : 'START',
            apis               : null,
            stage              : 'prod',
            region             : 'us-east-1',
            roleArn            : null,
            mode               : 'sequential',
            steps              : [10, 25, 50],
            bakeMinutes        : 10,
            approvals          : [perStep: true, beforePromote: true, timeoutMinutes: 60],
            gate               : [
                minRequests        : 100,
                requireTraffic     : false,
                max5xxRate         : 0.01d,
                maxP99LatencyMs    : null,
                pollSeconds        : 60,
                metricDelaySeconds : 120,
                maxExtensionMinutes: 10,
            ],
            monitor            : [
                enabled          : true,
                tfBinary         : 'terraform',
                namePrefix       : 'aws-progressive-delivery',
                periodSeconds    : 60,
                evaluationPeriods: 5,
                datapointsToAlarm: 3,
                alarmActions     : [],
                timeoutMinutes   : 15,
                backend          : [type: 's3', config: [:], configFile: null, keyPrefix: 'aws-progressive-delivery'],
            ],
            canary             : [useStageCache: false, stageVariableOverrides: [:]],
            onPeerFailure      : 'ask',
            canaryMetricStage  : null,
            adoptForeignCanary : false,
        ]
    }

    static Map build(Map overrides) {
        Map cfg = merge(defaults(), overrides ?: [:])
        cfg.action = String.valueOf(cfg.action).toUpperCase(Locale.ROOT)
        cfg.steps = parseSteps(cfg.steps)

        require(ACTIONS.contains(cfg.action), "action must be one of ${ACTIONS}")
        require(MODES.contains(cfg.mode), "mode must be one of ${MODES}")
        require(['ask', 'revert', 'keep'].contains(cfg.onPeerFailure), 'onPeerFailure must be ask, revert or keep')
        require(['s3', 'local'].contains(cfg.monitor.backend.type), 'monitor.backend.type must be s3 or local')
        require((cfg.gate.max5xxRate as double) >= 0d && (cfg.gate.max5xxRate as double) <= 1d, 'gate.max5xxRate must be between 0 and 1')
        require((cfg.bakeMinutes as int) >= 0, 'bakeMinutes must not be negative')
        require((cfg.gate.pollSeconds as int) >= 1, 'gate.pollSeconds must be at least 1')

        cfg.targets = parseTargets(cfg.apis, cfg.stage)
        require(!cfg.targets.isEmpty(), 'apis must list at least one API name')
        require(cfg.mode != 'single' || cfg.targets.size() == 1, "mode 'single' accepts exactly one API")
        return cfg
    }

    static List parseSteps(Object raw) {
        List values = raw instanceof CharSequence ? raw.toString().split(',').toList() : (raw as List)
        List steps = []
        for (Object value : values) {
            String text = String.valueOf(value).trim()
            if (text.isEmpty()) {
                continue
            }
            require(text ==~ /\d{1,2}/, "invalid canary step '${text}'")
            steps << Integer.parseInt(text)
        }
        require(!steps.isEmpty(), 'steps must list at least one percentage')
        for (int i = 0; i < steps.size(); i++) {
            require(steps[i] >= 1 && steps[i] <= 99, "step ${steps[i]} must be between 1 and 99")
            require(i == 0 || steps[i] > steps[i - 1], 'steps must be strictly increasing')
        }
        return steps
    }

    static List parseTargets(Object raw, String defaultStage) {
        List entries = raw == null ? [] : (raw instanceof CharSequence ? raw.toString().split(',').toList() : (raw as List))
        List targets = []
        List seen = []
        for (Object entry : entries) {
            String text = String.valueOf(entry).trim()
            if (text.isEmpty()) {
                continue
            }
            String name = text
            String stage = defaultStage
            int at = text.indexOf('@')
            if (at >= 0) {
                name = text.substring(0, at).trim()
                stage = text.substring(at + 1).trim()
            }
            require(!name.isEmpty() && !stage.isEmpty(), "invalid target '${text}'")
            String label = name + '@' + stage
            require(!seen.contains(label), "duplicate target ${label}")
            seen << label
            targets << [name: name, stage: stage, apiName: name, label: label]
        }
        return targets
    }

    static Map merge(Map base, Map overrides) {
        Map result = new LinkedHashMap(base)
        for (Object key : overrides.keySet()) {
            Object value = overrides[key]
            if (value == null) {
                continue
            }
            if (value instanceof Map && result[key] instanceof Map) {
                result[key] = merge((Map) result[key], (Map) value)
            } else {
                result[key] = value
            }
        }
        return result
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message)
        }
    }
}
