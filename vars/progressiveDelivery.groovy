import progressivedelivery.AwsGateway
import progressivedelivery.JenkinsPipeline
import progressivedelivery.NoopMonitor
import progressivedelivery.Orchestrator
import progressivedelivery.ReleaseConfig
import progressivedelivery.TerraformMonitor

def call(Map args = [:]) {
    Map cfg = ReleaseConfig.build(args)
    def pipeline = new JenkinsPipeline(this)
    def gateway = new AwsGateway(pipeline, cfg.region as String, cfg.roleArn as String)
    def monitor = cfg.monitor.enabled
        ? new TerraformMonitor(pipeline, gateway, cfg, gateway.prelude())
        : new NoopMonitor()

    pipeline.withEnvVars(["AWS_DEFAULT_REGION=${cfg.region}".toString()]) {
        new Orchestrator(pipeline, gateway, monitor, cfg).run()
    }
}
