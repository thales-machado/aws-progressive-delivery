import progressivedelivery.*

@groovy.transform.Field int passed = 0
@groovy.transform.Field List failures = []
@groovy.transform.Field File root = new File(System.getProperty('repo.root', '.'))

def check(String name, Closure body) {
    try {
        body()
        println "ok   ${name}"
        passed++
    } catch (Throwable e) {
        println "FAIL ${name}: ${e.message}"
        failures << name
    }
}

def expect(boolean condition, String message = 'assertion failed') {
    if (!condition) { throw new AssertionError(message) }
}

def expectEvents(List actual, List expected) {
    expect(actual == expected, "events\n  expected ${expected}\n  actual   ${actual}")
}

def expectThrows(Class type, Closure body) {
    try { body() } catch (Throwable e) {
        if (type.isInstance(e)) { return e }
        throw new AssertionError("expected ${type.simpleName}, got ${e.class.simpleName}: ${e.message}")
    }
    throw new AssertionError("expected ${type.simpleName}")
}

Map world(Map overrides = [:], List apis = ['orders']) {
    List events = []
    FakePipeline p = new FakePipeline(events, new File(root, 'resources'))
    FakeGateway g = new FakeGateway(events)
    FakeMonitor m = new FakeMonitor(events)
    apis.each { g.addApi(it as String) }
    Map base = [apis: apis.join(','), steps: '10,50', bakeMinutes: 2, gate: [pollSeconds: 60, metricDelaySeconds: 0, maxExtensionMinutes: 2]]
    Map cfg = ReleaseConfig.build(ReleaseConfig.merge(base, overrides))
    return [events: events, p: p, g: g, m: m, cfg: cfg, o: new Orchestrator(p, g, m, cfg)]
}

def relevant(List events) { events.findAll { !it.startsWith('parallel') } }

check('config defaults and parsing') {
    Map cfg = ReleaseConfig.build([apis: 'a, b@dev', steps: '5,20'])
    expect(cfg.steps == [5, 20])
    expect(cfg.targets.collect { it.label } == ['a@prod', 'b@dev'])
    expect(cfg.gate.minRequests == 100 && cfg.approvals.timeoutMinutes == 60)
}
check('config rejects bad input') {
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([apis: 'a', steps: '50,10']) }
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([apis: 'a', steps: '0,10']) }
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([apis: 'a,a']) }
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([apis: 'a,b', mode: 'single']) }
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([apis: 'a', action: 'NOPE']) }
    expectThrows(IllegalArgumentException) { ReleaseConfig.build([steps: '10']) }
}
check('health gate') {
    Map gate = ReleaseConfig.defaults().gate
    expect(HealthGate.evaluate([requests: 1000d, errors: 5d, p99Ms: 100d], gate).status == 'PASS')
    expect(HealthGate.evaluate([requests: 1000d, errors: 50d, p99Ms: 100d], gate).status == 'FAIL')
    expect(HealthGate.evaluate([requests: 10d, errors: 10d, p99Ms: null], gate).status == 'INSUFFICIENT')
    Map latency = gate + [maxP99LatencyMs: 200]
    expect(HealthGate.evaluate([requests: 1000d, errors: 0d, p99Ms: 500d], latency).status == 'FAIL')
}
check('happy path single target') {
    Map w = world()
    w.o.run()
    expectEvents(w.events, ['choose:PROCEED', 'monitor-apply:id-orders', 'create:id-orders:10', 'choose:PROCEED', 'percent:id-orders:50',
                            'choose:PROMOTE', 'monitor-destroy:id-orders', 'promote:id-orders'])
    expect(w.g.stages['id-orders'].deploymentId == 'canary-1')
    expect(w.g.stages['id-orders'].canarySettings == null)
    expect(w.g.descriptions['canary-1'].startsWith(Orchestrator.MARKER))
    expect(w.p.prompts[0].choices == ['ABORT', 'PROCEED'])
    expect(w.p.prompts[1].choices == ['ABORT', 'PROCEED', 'HOLD'])
    expect(w.p.prompts[2].choices == ['ABORT', 'PROMOTE', 'HOLD'])
}
check('gate failure is urgent: silence, rollback, then destroy') {
    Map w = world()
    w.g.metricsFor = { String api, long from, long to -> [requests: 1000d, errors: 100d, p99Ms: 1d] }
    expectThrows(GateFailed) { w.o.run() }
    expectEvents(w.events, ['choose:PROCEED', 'monitor-apply:id-orders', 'create:id-orders:10', 'monitor-silence:id-orders',
                            'removeCanary:id-orders', 'delete:id-orders:canary-1', 'monitor-destroy:id-orders'])
    expect(w.g.stages['id-orders'].canarySettings == null && w.g.stages['id-orders'].deploymentId == 'stable-orders')
}
check('abort destroys monitor before removing canary') {
    Map w = world()
    int n = 0
    w.p.decider = { String m, List c -> ++n == 2 ? 'ABORT' : c[1] }
    expectThrows(ReleaseAborted) { w.o.run() }
    expectEvents(w.events, ['choose:PROCEED', 'monitor-apply:id-orders', 'create:id-orders:10', 'choose:ABORT',
                            'monitor-destroy:id-orders', 'removeCanary:id-orders', 'delete:id-orders:canary-1'])
}
check('abort at first approval touches nothing') {
    Map w = world()
    w.p.decider = { String m, List c -> 'ABORT' }
    expectThrows(ReleaseAborted) { w.o.run() }
    expectEvents(w.events, ['choose:ABORT'])
}
check('hold then continue') {
    Map w = world()
    int n = 0
    w.p.decider = { String m, List c -> ++n == 2 ? 'HOLD' : c[1] }
    w.o.run()
    expect(w.g.stages['id-orders'].canarySettings.percentTraffic == 10d)
    expect(!w.events.any { it.startsWith('monitor-destroy') })
    w.events.clear()

    Map cont = world([action: 'CONTINUE'])
    cont.g.stages = w.g.stages
    cont.g.descriptions = w.g.descriptions
    cont.events.clear()
    cont.o.run()
    expectEvents(cont.events, ['choose:PROCEED', 'monitor-apply:id-orders', 'percent:id-orders:50', 'choose:PROMOTE',
                               'monitor-destroy:id-orders', 'promote:id-orders'])
}
check('START refuses an existing canary, CONTINUE refuses none or foreign') {
    Map w = world()
    w.g.stages['id-orders'].canarySettings = [deploymentId: 'x', percentTraffic: 10d]
    expectThrows(IllegalStateException) { w.o.run() }
    Map c = world([action: 'CONTINUE'])
    expectThrows(IllegalStateException) { c.o.run() }
    Map f = world([action: 'CONTINUE'])
    f.g.stages['id-orders'].canarySettings = [deploymentId: 'x', percentTraffic: 10d]
    expectThrows(IllegalStateException) { f.o.run() }
    Map ok = world([action: 'CONTINUE', adoptForeignCanary: true])
    ok.g.stages['id-orders'].canarySettings = [deploymentId: 'x', percentTraffic: 10d]
    ok.o.run()
    expect(ok.g.stages['id-orders'].deploymentId == 'x')
}
check('approval timeout falls back to ABORT') {
    Map w = world()
    w.p.decider = { String m, List c -> c[0] }
    expectThrows(ReleaseAborted) { w.o.run() }
}
check('insufficient traffic extends the bake then continues or fails') {
    Map w = world([bakeMinutes: 1, steps: '10', approvals: [perStep: false, beforePromote: false]])
    w.g.metricsFor = { String api, long from, long to -> [requests: 0d, errors: 0d, p99Ms: null] }
    long start = w.p.clock
    w.o.run()
    expect(w.p.clock - start >= 180, 'bake was not extended')
    Map strict = world([bakeMinutes: 1, steps: '10', gate: [requireTraffic: true, metricDelaySeconds: 0, maxExtensionMinutes: 1], approvals: [perStep: false, beforePromote: false]])
    strict.g.metricsFor = { String api, long from, long to -> [requests: 0d, errors: 0d, p99Ms: null] }
    expectThrows(GateFailed) { strict.o.run() }
    expect(strict.g.stages['id-orders'].canarySettings == null)
}
check('parallel mode fails fast and reverts everything') {
    Map w = world([mode: 'parallel'], ['a', 'b'])
    w.g.failOn << 'createCanary:id-b'
    expectThrows(IllegalStateException) { w.o.run() }
    expect(w.events.any { it.startsWith('parallel:') })
    expect(w.g.stages['id-a'].canarySettings == null && w.g.stages['id-b'].canarySettings == null)
    expect(w.events.indexOf('monitor-silence:id-a') < w.events.indexOf('removeCanary:id-a'))
}
check('parallel mode promotes together') {
    Map w = world([mode: 'parallel'], ['a', 'b'])
    w.o.run()
    expect(w.g.stages['id-a'].deploymentId == 'canary-1' && w.g.stages['id-b'].deploymentId == 'canary-2')
    expect(w.events.count { it.startsWith('choose') } == 3, 'approvals must be shared')
}
check('sequential: later failure reverts promoted peer by default') {
    Map w = world([steps: '10', approvals: [perStep: false, beforePromote: false]], ['a', 'b'])
    w.g.failOn << 'createCanary:id-b'
    w.p.decider = { String m, List c -> c[0] == 'REVERT' ? 'REVERT' : c[1] }
    expectThrows(IllegalStateException) { w.o.run() }
    expect(w.g.stages['id-a'].deploymentId == 'stable-a', 'a must be reverted')
    expect(w.events.contains('restore:id-a:stable-a'))
    expect(w.p.prompts.size() == 1 && w.p.prompts[0].choices == ['REVERT', 'KEEP'])
}
check('sequential: KEEP leaves promoted peer') {
    Map w = world([steps: '10', approvals: [perStep: false, beforePromote: false]], ['a', 'b'])
    w.g.failOn << 'createCanary:id-b'
    w.p.decider = { String m, List c -> 'KEEP' }
    expectThrows(IllegalStateException) { w.o.run() }
    expect(w.g.stages['id-a'].deploymentId == 'canary-1')
}
check('sequential: onPeerFailure keep skips the prompt') {
    Map w = world([steps: '10', onPeerFailure: 'keep', approvals: [perStep: false, beforePromote: false]], ['a', 'b'])
    w.g.failOn << 'createCanary:id-b'
    expectThrows(IllegalStateException) { w.o.run() }
    expect(w.p.prompts.isEmpty())
}
check('sequential: hold stops before next target; later failure asks nobody') {
    Map w = world([steps: '10,50'], ['a', 'b'])
    int n = 0
    w.p.decider = { String m, List c -> ++n == 2 ? 'HOLD' : c[1] }
    w.o.run()
    expect(w.g.stages['id-b'].canarySettings == null && w.g.stages['id-a'].canarySettings != null)
    Map c = world([action: 'CONTINUE', steps: '10,50'], ['a', 'b'])
    c.g.stages = w.g.stages
    c.g.descriptions = w.g.descriptions
    c.g.failOn << 'promote:id-a'
    expectThrows(IllegalStateException) { c.o.run() }
    expect(c.p.prompts.every { it.choices != ['REVERT', 'KEEP'] })
}
check('monitor destroy failure is not fatal') {
    Map w = world()
    w.m.failDestroy = true
    w.g.alarms = ['x']
    w.o.run()
    expect(w.g.stages['id-orders'].deploymentId == 'canary-1')
    expect(w.p.unstableMessages.any { it.contains('CLEANUP') })
    expect(w.events.contains('monitor-silence:id-orders'))
}
check('promote failure reverts the promoted target') {
    Map w = world([mode: 'parallel', steps: '10', approvals: [perStep: false, beforePromote: false]], ['a', 'b'])
    w.g.failOn << 'promote:id-b'
    expectThrows(IllegalStateException) { w.o.run() }
    expect(w.g.stages['id-a'].deploymentId == 'stable-a' && w.g.stages['id-b'].canarySettings == null)
    expect(w.g.stages['id-a'].variables == [version: 'v1'])
}
check('manual PROMOTE, ROLLBACK, CLEANUP, STATUS') {
    Map w = world([action: 'PROMOTE'])
    w.g.stages['id-orders'].canarySettings = [deploymentId: 'c', percentTraffic: 10d, stageVariableOverrides: [version: 'v2']]
    w.o.run()
    expect(w.g.stages['id-orders'].deploymentId == 'c' && w.g.stages['id-orders'].variables.version == 'v2')

    Map r = world([action: 'ROLLBACK'])
    r.g.stages['id-orders'].canarySettings = [deploymentId: 'c', percentTraffic: 10d]
    r.o.run()
    expect(r.g.stages['id-orders'].canarySettings == null && r.events.contains('delete:id-orders:c'))

    Map c = world([action: 'CLEANUP'])
    c.o.run()
    expectEvents(c.events, ['monitor-destroy:id-orders'])

    Map s = world([action: 'STATUS'])
    s.g.stages['id-orders'].canarySettings = [deploymentId: 'c', percentTraffic: 10d]
    s.o.run()
    expect(s.p.logs.any { it.contains('canary=c at 10.0%') })
}
check('AwsGateway builds the expected CLI calls') {
    List events = []
    FakePipeline p = new FakePipeline(events, new File(root, 'resources'))
    AwsGateway gw = new AwsGateway(p, 'eu-west-1', 'arn:aws:iam::123456789012:role/release')
    p.shResult = '["abc123"]'
    expect(gw.restApiId('orders') == 'abc123')
    p.shResult = 'dep-9'
    gw.createCanary('abc123', 'prod', 10, [version: 'v2'], false, "marker's")
    String create = p.scripts.last()
    expect(create.startsWith('set +x'))
    expect(create.contains('aws sts assume-role'))
    expect(create.contains('--canary-settings \'{"percentTraffic":10.0,"useStageCache":false,"stageVariableOverrides":{"version":"v2"}}\''), create)
    expect(create.contains("--description 'marker'\\''s'"), create)
    gw.setPercent('abc123', 'prod', 25)
    expect(p.scripts.last().contains('"value":"25.0"'))
    gw.promote('abc123', 'prod', 'dep-9', [version: 'v2'])
    expect(p.scripts[-2].contains('"op":"copy","from":"/canarySettings/deploymentId","path":"/deploymentId"'))
    expect(p.scripts[-1].contains('"op":"remove","path":"/canarySettings"'))
    p.shResult = '{"MetricDataResults":[{"Id":"requests","Values":[10,20]},{"Id":"errors","Values":[1]},{"Id":"latency","Values":[100,250]}]}'
    Map m = gw.metrics('orders', 'prod/Canary', 1700000000L, 1700000300L)
    expect(m.requests == 30d && m.errors == 1d && m.p99Ms == 250d)
    expect(p.scripts.last().contains('--start-time 2023-11-14T22:13:20Z'), p.scripts.last())
    expect(!new AwsGateway(p, 'us-east-1').prelude().contains('assume-role'))
}
check('TerraformMonitor generates files and commands') {
    List events = []
    FakePipeline p = new FakePipeline(events, new File(root, 'resources'))
    Map cfg = ReleaseConfig.build([apis: 'orders', monitor: [backend: [config: [bucket: 'state-bucket', region: 'us-east-1']]], gate: [maxP99LatencyMs: 800]])
    TerraformMonitor tm = new TerraformMonitor(p, new FakeGateway(events), cfg, 'set +x\n')
    Map t = [apiName: 'orders', stage: 'prod', restApiId: 'abc']
    tm.apply(t)
    String dir = '.progressive-delivery/abc-prod'
    TerraformMonitor.FILES.each { expect(p.files.containsKey("${dir}/${it}".toString()), it) }
    expect(p.files["${dir}/backend.tf".toString()].contains('backend "s3"'))
    expect(p.files["${dir}/terraform.tfvars.json".toString()].contains('"max_p99_latency_ms": 800'))
    String script = p.scripts.last()
    expect(script.contains("-backend-config='key=aws-progressive-delivery/abc/prod/monitor.tfstate'"), script)
    expect(script.contains("-backend-config='bucket=state-bucket'"))
    expect(script.contains('apply -input=false -auto-approve'))
    tm.destroy(t)
    expect(p.scripts.last().contains('destroy -input=false -auto-approve'))
    expect(tm.alarmPrefix(t) == 'aws-progressive-delivery-orders-prod-')
}

println "\n${passed} passed, ${failures.size()} failed"
System.exit(failures.isEmpty() ? 0 : 1)
