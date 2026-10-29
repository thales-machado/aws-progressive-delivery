package progressivedelivery

/** The API Gateway and CloudWatch operations a release needs. */
interface Gateway {

    String restApiId(String name)

    /** Returns the stage as the API describes it: deploymentId, variables and canarySettings (null without a canary). */
    Map getStage(String restApiId, String stage)

    String deploymentDescription(String restApiId, String deploymentId)

    /** Creates a deployment from the current API definition and routes the given percent of traffic to it. Returns its ID. */
    String createCanary(String restApiId, String stage, Number percent, Map overrides, boolean useStageCache, String description)

    void setPercent(String restApiId, String stage, Number percent)

    void promote(String restApiId, String stage, String canaryDeploymentId, Map overrides)

    void removeCanary(String restApiId, String stage)

    /** Points the stage back at a deployment. A null variable value removes that variable. */
    void restoreStable(String restApiId, String stage, String deploymentId, Map variables)

    void deleteDeployment(String restApiId, String deploymentId)

    /** Returns requests, errors and p99Ms (null without data) for the canary over the window. */
    Map metrics(String apiName, String metricStage, long fromEpoch, long toEpoch)

    List alarmNames(String prefix)

    void silenceAlarms(List names)
}
