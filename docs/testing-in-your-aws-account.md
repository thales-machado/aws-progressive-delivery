# Testing in your own AWS account

Everything here is cheap, but it creates real resources. Use a sandbox account and tear it down afterwards.

## Before you start

1. Create a budget alert (for example USD 5) in AWS Budgets.
2. Use a state bucket you can delete: `aws s3 mb s3://<unique-name>`.
3. Prefer a role with [docs/iam-policy.json](iam-policy.json) over administrator credentials.

## Run

1. In `examples/apigw-basic`, copy `backend.tf.example` to `backend.tf`, edit it, then `terraform init && terraform apply`.
2. Start the Jenkins sandbox (`examples/jenkins-local`) or use your own Jenkins with the library registered.
3. Run `ACTION=START` with `API_NAMES=pd-example-orders`, `CANARY_STEPS=10,50`. Generate traffic against `health_url` (set
   `isCanaryRequest` field in the response tells you which side answered), for example:
   `for i in $(seq 1 500); do curl -s "$URL" >/dev/null; done`.
4. Exercise: promote, `ABORT`, `HOLD` then `CONTINUE`, a forced gate failure (`maxP99LatencyMs: 1`), and a two-API sequential run.

## Verify these against the real service

These points were not confirmable from documentation while building the library:

- [ ] `update-stage` accepts `replace /canarySettings/percentTraffic` with a value like `"25.0"` on an existing canary.
- [ ] Canary metrics appear in CloudWatch under `Stage=<stage>/Canary`. If the dimension differs, set `canaryMetricStage`.
- [ ] Detailed metrics are required for the canary dimension (`detailed_metrics_enabled = true` on the stage module).
- [ ] The deployment `description` accepts the marker `aws-progressive-delivery:<build tag>` at your build tag length.
- [ ] `replace /variables/<key>` succeeds when promoting a variable override that is new to the stage; if not, define the variable on the stage first.
- [ ] The input prompt timeout falls back to `ABORT` in your Jenkins version.
- [ ] R2 locking behaviour, if you use R2.

## Tear down

```sh
terraform -chdir=examples/apigw-basic destroy
aws s3 rb s3://<unique-name> --force
```

Run `ACTION=CLEANUP` first if any alarms `aws-progressive-delivery-*` remain, and remove the Jenkins sandbox with
`docker compose -f examples/jenkins-local/docker-compose.yml down -v`.
