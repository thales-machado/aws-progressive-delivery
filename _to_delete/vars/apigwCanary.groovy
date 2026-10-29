import groovy.json.JsonOutput

/**
 * Gradual release of a new deployment on an API Gateway REST API stage.
 *
 * Creates a deployment from the current API definition, shifts traffic to it in steps,
 * checks the canary 5XX rate after each bake window, and promotes it. Any failure,
 * abort or approval timeout puts 100% of the traffic back on the previous deployment.
 *
 * Required: restApiId, apiName, stageName.
 * tfDir must be a root module that passes stable_deployment_id and canary to this module.
 */
def call(Map cfg) {
    Map c = [
        region                : 'us-east-1',
        tfDir                 : '.',
        steps                 : [10, 25, 50],
        bakeMinutes           : 10,
        max5xxRate            : 0.01,
        minRequests           : 100,
        requireTraffic        : false,
        manualPromotion       : true,
        approvalTimeoutMinutes: 60,
        stageVariableOverrides: [:],
    ] + cfg

    ['restApiId', 'apiName', 'stageName'].each {
        if (!c[it]) {
            error("apigwCanary: '${it}' is required")
        }
    }
    c.canaryMetricStage = c.canaryMetricStage ?: "${c.stageName}/Canary"

    String stableId = aws(c, "apigateway get-stage --rest-api-id ${c.restApiId} --stage-name ${c.stageName} --query deploymentId --output text")
    String canaryId = aws(c, "apigateway create-deployment --rest-api-id ${c.restApiId} --description '${env.BUILD_TAG}' --query id --output text")
    echo "apigwCanary: stable=${stableId} canary=${canaryId}"

    dir(c.tfDir) {
        sh 'terraform init -input=false'
    }

    try {
        c.steps.each { pct ->
            stage("Canary ${pct}%") {
                apply(c, stableId, [
                    deployment_id           : canaryId,
                    percent_traffic         : pct,
                    stage_variable_overrides: c.stageVariableOverrides,
                ])
                sleep(time: c.bakeMinutes, unit: 'MINUTES')
                assertHealthy(c)
            }
        }

        if (c.manualPromotion) {
            stage('Approve promotion') {
                timeout(time: c.approvalTimeoutMinutes, unit: 'MINUTES') {
                    input(message: "Promote ${canaryId} to 100% of ${c.stageName}?", ok: 'Promote')
                }
            }
        }

        stage('Promote') {
            apply(c, canaryId, null)
        }
    } catch (err) {
        stage('Rollback') {
            apply(c, stableId, null)
        }
        throw err
    }

    return canaryId
}

private String aws(Map c, String args) {
    return sh(script: "aws --region ${c.region} ${args}", returnStdout: true).trim()
}

private void apply(Map c, String stableId, Map canary) {
    dir(c.tfDir) {
        writeFile(
            file: 'canary.auto.tfvars.json',
            text: JsonOutput.toJson([stable_deployment_id: stableId, canary: canary])
        )
        sh 'terraform apply -input=false -auto-approve'
    }
}

private double metricSum(Map c, String metric) {
    String out = aws(c, [
        'cloudwatch get-metric-statistics',
        '--namespace AWS/ApiGateway',
        "--metric-name ${metric}",
        "--dimensions Name=ApiName,Value=${c.apiName} Name=Stage,Value=${c.canaryMetricStage}",
        "--start-time \$(date -u -d '-${c.bakeMinutes} min' +%Y-%m-%dT%H:%M:%SZ)",
        "--end-time \$(date -u +%Y-%m-%dT%H:%M:%SZ)",
        "--period ${c.bakeMinutes * 60}",
        '--statistics Sum',
        "--query 'sum(Datapoints[].Sum)'",
        '--output text',
    ].join(' '))
    return out ? out.toDouble() : 0d
}

private void assertHealthy(Map c) {
    double requests = metricSum(c, 'Count')
    double errors = metricSum(c, '5XXError')

    if (requests < c.minRequests) {
        String msg = "apigwCanary: only ${requests as long} canary requests in ${c.bakeMinutes} min (min ${c.minRequests})"
        if (c.requireTraffic) {
            error(msg)
        }
        echo "${msg}, 5XX gate skipped"
        return
    }

    double rate = errors / requests
    echo "apigwCanary: canary 5XX rate ${String.format('%.4f', rate)} (${errors as long}/${requests as long}), limit ${c.max5xxRate}"
    if (rate > c.max5xxRate) {
        error("apigwCanary: canary 5XX rate ${rate} above ${c.max5xxRate}")
    }
}
