package progressivedelivery

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

class AwsGateway implements Gateway, Serializable {

    private final Pipeline pipeline
    private final String region
    private final String roleArn

    AwsGateway(Pipeline pipeline, String region, String roleArn = null) {
        this.pipeline = pipeline
        this.region = region
        this.roleArn = roleArn
    }

    /** Shell preamble shared with Terraform. Credentials are re-assumed per command so long soaks never hit expiry. */
    String prelude() {
        String script = 'set +x\nexport AWS_PAGER=""\n'
        if (roleArn) {
            script += 'CREDS=$(aws sts assume-role --role-arn ' + Shell.quote(roleArn) +
                ' --role-session-name aws-progressive-delivery --region ' + Shell.quote(region) +
                " --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text)\n" +
                'set -- $CREDS\n' +
                'export AWS_ACCESS_KEY_ID=$1 AWS_SECRET_ACCESS_KEY=$2 AWS_SESSION_TOKEN=$3\n'
        }
        return script
    }

    String restApiId(String name) {
        String out = aws('apigateway get-rest-apis --query ' + Shell.quote("items[?name=='" + name + "'].id") + ' --output json')
        List ids = new JsonSlurperClassic().parseText(out) as List
        if (ids.isEmpty()) {
            throw new IllegalStateException("No REST API named '${name}' in ${region}")
        }
        if (ids.size() > 1) {
            throw new IllegalStateException("REST API name '${name}' is ambiguous in ${region}: ${ids}")
        }
        return ids[0] as String
    }

    Map getStage(String restApiId, String stage) {
        String out = aws("apigateway get-stage --rest-api-id ${Shell.quote(restApiId)} --stage-name ${Shell.quote(stage)} --output json")
        return new JsonSlurperClassic().parseText(out) as Map
    }

    String deploymentDescription(String restApiId, String deploymentId) {
        String out = aws("apigateway get-deployment --rest-api-id ${Shell.quote(restApiId)} --deployment-id ${Shell.quote(deploymentId)}" +
            ' --query description --output text')
        return out == 'None' ? '' : out
    }

    String createCanary(String restApiId, String stage, Number percent, Map overrides, boolean useStageCache, String description) {
        Map settings = [percentTraffic: (percent as double), useStageCache: useStageCache]
        if (overrides) {
            settings.stageVariableOverrides = overrides
        }
        return aws("apigateway create-deployment --rest-api-id ${Shell.quote(restApiId)} --stage-name ${Shell.quote(stage)}" +
            " --description ${Shell.quote(description)} --canary-settings ${Shell.quote(JsonOutput.toJson(settings))}" +
            ' --query id --output text')
    }

    void setPercent(String restApiId, String stage, Number percent) {
        patch(restApiId, stage, [[op: 'replace', path: '/canarySettings/percentTraffic', value: percentText(percent)]])
    }

    void promote(String restApiId, String stage, String canaryDeploymentId, Map overrides) {
        List ops = [[op: 'replace', path: '/canarySettings/percentTraffic', value: '0.0']]
        for (Object key : overrides.keySet()) {
            ops << [op: 'replace', path: '/variables/' + key, value: String.valueOf(overrides[key])]
        }
        ops << [op: 'copy', from: '/canarySettings/deploymentId', path: '/deploymentId']
        patch(restApiId, stage, ops)
        removeCanary(restApiId, stage)
    }

    void removeCanary(String restApiId, String stage) {
        patch(restApiId, stage, [[op: 'remove', path: '/canarySettings']])
    }

    void restoreStable(String restApiId, String stage, String deploymentId, Map variables) {
        List ops = [[op: 'replace', path: '/deploymentId', value: deploymentId]]
        for (Object key : variables.keySet()) {
            Object value = variables[key]
            ops << (value == null
                ? [op: 'remove', path: '/variables/' + key]
                : [op: 'replace', path: '/variables/' + key, value: String.valueOf(value)])
        }
        patch(restApiId, stage, ops)
    }

    void deleteDeployment(String restApiId, String deploymentId) {
        aws("apigateway delete-deployment --rest-api-id ${Shell.quote(restApiId)} --deployment-id ${Shell.quote(deploymentId)}")
    }

    Map metrics(String apiName, String metricStage, long fromEpoch, long toEpoch) {
        if (toEpoch <= fromEpoch) {
            return [requests: 0d, errors: 0d, p99Ms: null]
        }
        long window = toEpoch - fromEpoch
        int latencyPeriod = (int) (Math.max(1L, (window + 59L).intdiv(60L)) * 60L)
        List dimensions = [[Name: 'ApiName', Value: apiName], [Name: 'Stage', Value: metricStage]]
        List queries = [
            query('requests', 'Count', 'SampleCount', 60, dimensions),
            query('errors', '5XXError', 'Sum', 60, dimensions),
            query('latency', 'Latency', 'p99', latencyPeriod, dimensions),
        ]
        String out = aws('cloudwatch get-metric-data --scan-by TimestampAscending' +
            " --start-time ${isoTime(fromEpoch)} --end-time ${isoTime(toEpoch)}" +
            " --metric-data-queries ${Shell.quote(JsonOutput.toJson(queries))} --output json")
        List results = (new JsonSlurperClassic().parseText(out) as Map).MetricDataResults as List
        Map byId = [:]
        for (Object result : results) {
            byId[result.Id] = (result.Values ?: []) as List
        }
        List latencies = byId.latency ?: []
        return [
            requests: sum(byId.requests),
            errors  : sum(byId.errors),
            p99Ms   : latencies.isEmpty() ? null : latencies.collect { it as double }.max(),
        ]
    }

    List alarmNames(String prefix) {
        String out = aws("cloudwatch describe-alarms --alarm-name-prefix ${Shell.quote(prefix)} --query 'MetricAlarms[].AlarmName' --output json")
        return new JsonSlurperClassic().parseText(out) as List
    }

    void silenceAlarms(List names) {
        String quoted = names.collect { Shell.quote(it) }.join(' ')
        aws("cloudwatch disable-alarm-actions --alarm-names ${quoted}")
    }

    private void patch(String restApiId, String stage, List ops) {
        aws("apigateway update-stage --rest-api-id ${Shell.quote(restApiId)} --stage-name ${Shell.quote(stage)}" +
            " --patch-operations ${Shell.quote(JsonOutput.toJson(ops))}")
    }

    private String aws(String args) {
        return pipeline.sh(prelude() + 'aws --region ' + Shell.quote(region) + ' ' + args)
    }

    private static Map query(String id, String metric, String stat, int period, List dimensions) {
        return [
            Id        : id,
            MetricStat: [
                Metric: [Namespace: 'AWS/ApiGateway', MetricName: metric, Dimensions: dimensions],
                Period: period,
                Stat  : stat,
            ],
            ReturnData: true,
        ]
    }

    private static double sum(Object values) {
        double total = 0d
        for (Object value : (values ?: [])) {
            total += (value as double)
        }
        return total
    }

    private static String percentText(Number percent) {
        return String.format(Locale.ROOT, '%.1f', percent as double)
    }

    private static String isoTime(long epoch) {
        return java.time.Instant.ofEpochSecond(epoch).toString()
    }
}
