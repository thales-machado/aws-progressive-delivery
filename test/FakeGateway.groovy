import progressivedelivery.Gateway
import progressivedelivery.Monitor
import progressivedelivery.Pipeline

class FakeGateway implements Gateway {
    List events
    Map stages = [:]
    Map descriptions = [:]
    Set failOn = [] as Set
    Closure metricsFor = { String api, long from, long to -> [requests: 1000d, errors: 0d, p99Ms: 120d] }
    List alarms = []
    int counter = 0

    FakeGateway(List events) { this.events = events }

    void addApi(String name, String stage = 'prod', Map variables = [version: 'v1']) {
        stages["id-${name}".toString()] = [deploymentId: "stable-${name}".toString(), variables: variables, canarySettings: null]
    }

    private Map st(String id) { return stages[id] as Map }
    private void maybeFail(String op, String id) { if (failOn.contains(op + ':' + id) || failOn.contains(op)) throw new IllegalStateException("injected ${op} failure") }

    String restApiId(String name) { return "id-${name}".toString() }
    Map getStage(String id, String stage) { return new LinkedHashMap(st(id)) }
    String deploymentDescription(String id, String dep) { return descriptions[dep] ?: '' }

    String createCanary(String id, String stage, Number percent, Map overrides, boolean cache, String description) {
        maybeFail('createCanary', id)
        String dep = "canary-${++counter}".toString()
        st(id).canarySettings = [deploymentId: dep, percentTraffic: percent as double, stageVariableOverrides: overrides, useStageCache: cache]
        descriptions[dep] = description
        events << "create:${id}:${percent}".toString()
        return dep
    }

    void setPercent(String id, String stage, Number percent) {
        maybeFail('setPercent', id)
        st(id).canarySettings.percentTraffic = percent as double
        events << "percent:${id}:${percent}".toString()
    }

    void promote(String id, String stage, String dep, Map overrides) {
        maybeFail('promote', id)
        st(id).deploymentId = dep
        overrides.each { k, v -> st(id).variables[k] = v }
        st(id).canarySettings = null
        events << "promote:${id}".toString()
    }

    void removeCanary(String id, String stage) {
        maybeFail('removeCanary', id)
        st(id).canarySettings = null
        events << "removeCanary:${id}".toString()
    }

    void restoreStable(String id, String stage, String dep, Map variables) {
        maybeFail('restoreStable', id)
        st(id).deploymentId = dep
        variables.each { k, v -> if (v == null) st(id).variables.remove(k) else st(id).variables[k] = v }
        events << "restore:${id}:${dep}".toString()
    }

    void deleteDeployment(String id, String dep) { events << "delete:${id}:${dep}".toString() }
    Map metrics(String api, String metricStage, long from, long to) { return metricsFor(api, from, to) }
    List alarmNames(String prefix) { return alarms }
    void silenceAlarms(List names) { events << "silenceAlarms".toString() }
}
