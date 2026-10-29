# Credentials

The library does not manage secrets. It uses whatever AWS identity the agent already has and can optionally assume a role.

| Option | How |
| --- | --- |
| Ambient identity | Instance profile, IRSA, OIDC federation or `~/.aws` on the agent. Nothing to configure. |
| Assume a role | `roleArn: 'arn:aws:iam::123456789012:role/release'`. Credentials are requested again for every command, so a multi-day soak never holds expired ones. Commands run with `set +x`. |
| Secret store | Wrap the call with your own credentials binding, for example Vault or Secrets Manager injected as environment variables, and call `progressiveDelivery` inside it. |

The AWS pager is disabled (`AWS_PAGER=""`) and no credential is printed. Terraform receives the same role through the shared shell prelude.

## Permissions

[iam-policy.json](iam-policy.json) lists what the release role needs: API Gateway deployment and stage operations, CloudWatch
metrics and alarm management, and read/write on the state prefix. Restrict the `Resource` entries to your APIs, alarm prefix and bucket.
