package progressivedelivery

import groovy.json.JsonOutput

class TerraformMonitor implements Monitor, Serializable {

    static final List FILES = ['versions.tf', 'variables.tf', 'main.tf', 'outputs.tf']

    private final Pipeline pipeline
    private final Gateway gateway
    private final Map cfg
    private final String prelude

    TerraformMonitor(Pipeline pipeline, Gateway gateway, Map cfg, String prelude) {
        this.pipeline = pipeline
        this.gateway = gateway
        this.cfg = cfg
        this.prelude = prelude
    }

    void apply(Map target) {
        run(target, 'apply')
    }

    void destroy(Map target) {
        run(target, 'destroy')
    }

    void silence(Map target) {
        List names = gateway.alarmNames(alarmPrefix(target))
        if (!names.isEmpty()) {
            gateway.silenceAlarms(names)
        }
    }

    String alarmPrefix(Map target) {
        return "${cfg.monitor.namePrefix}-${target.apiName}-${target.stage}-".toString()
    }

    String workDir(Map target) {
        return ".progressive-delivery/${target.restApiId}-${target.stage}".toString()
    }

    Map variables(Map target) {
        Map gate = cfg.gate
        Map monitor = cfg.monitor
        Map vars = [
            region             : cfg.region,
            api_name           : target.apiName,
            stage_name         : target.stage,
            name_prefix        : monitor.namePrefix,
            max_5xx_rate       : gate.max5xxRate,
            min_requests       : gate.minRequests,
            period_seconds     : monitor.periodSeconds,
            evaluation_periods : monitor.evaluationPeriods,
            datapoints_to_alarm: monitor.datapointsToAlarm,
            alarm_actions      : monitor.alarmActions,
        ]
        if (gate.maxP99LatencyMs != null) {
            vars.max_p99_latency_ms = gate.maxP99LatencyMs
        }
        if (cfg.canaryMetricStage != null) {
            vars.canary_stage_dimension = cfg.canaryMetricStage
        }
        return vars
    }

    List backendArgs(Map target) {
        Map backend = cfg.monitor.backend
        if (backend.type == 'local') {
            return []
        }
        List args = []
        if (backend.configFile) {
            args << "-backend-config=${Shell.quote(backend.configFile)}".toString()
        }
        Map config = new LinkedHashMap(backend.config ?: [:])
        config.key = "${backend.keyPrefix}/${target.restApiId}/${target.stage}/monitor.tfstate".toString()
        for (Object key : config.keySet()) {
            args << "-backend-config=${Shell.quote(key + '=' + config[key])}".toString()
        }
        return args
    }

    private void run(Map target, String command) {
        String dir = workDir(target)
        for (String file : FILES) {
            pipeline.writeText("${dir}/${file}".toString(), pipeline.resource("terraform/apigw-monitor/${file}".toString()))
        }
        if (cfg.monitor.backend.type == 's3') {
            pipeline.writeText("${dir}/backend.tf".toString(), 'terraform {\n  backend "s3" {}\n}\n')
        }
        pipeline.writeText("${dir}/terraform.tfvars.json".toString(), JsonOutput.prettyPrint(JsonOutput.toJson(variables(target))))

        String tf = "timeout ${cfg.monitor.timeoutMinutes}m ${Shell.quote(cfg.monitor.tfBinary)} -chdir=${Shell.quote(dir)}"
        String init = "${tf} init -input=false -reconfigure ${backendArgs(target).join(' ')}".trim()
        pipeline.sh(prelude + init + '\n' + "${tf} ${command} -input=false -auto-approve")
    }
}
