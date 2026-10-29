# apigw-canary

Creates an API Gateway REST API stage and hands the deployment pointers over to the release pipeline.

`deployment_id` and `canary_settings` are in `lifecycle.ignore_changes`: Terraform sets the stage up with the
deployment you give it, and from then on the pipeline owns which deployment is stable and which is the canary.
Running `terraform apply` while a canary is in flight does not touch it.

```hcl
module "stage" {
  source = "./modules/apigw-canary"

  rest_api_id           = aws_api_gateway_rest_api.this.id
  stage_name            = "prod"
  initial_deployment_id = aws_api_gateway_deployment.initial.id
  stage_variables       = { version = "v1" }
}
```

The deployment passed in must not use `triggers`: a deployment that is replaced on every API change would race the pipeline.
`detailed_metrics_enabled` (default `false`, adds CloudWatch cost) turns on per-method metrics; enable it if the canary metric dimension needs them.
