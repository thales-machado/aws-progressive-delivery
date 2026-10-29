output "stage_name" {
  description = "Name of the stage."
  value       = aws_api_gateway_stage.this.stage_name
}

output "stage_arn" {
  description = "ARN of the stage."
  value       = aws_api_gateway_stage.this.arn
}

output "invoke_url" {
  description = "Invoke URL of the stage."
  value       = aws_api_gateway_stage.this.invoke_url
}

output "execution_arn" {
  description = "Execution ARN of the stage, for Lambda permissions."
  value       = aws_api_gateway_stage.this.execution_arn
}

output "stable_deployment_id" {
  description = "Deployment serving the stable traffic."
  value       = aws_api_gateway_stage.this.deployment_id
}

output "canary_deployment_id" {
  description = "Deployment serving the canary traffic, or null when no canary is active."
  value       = try(var.canary.deployment_id, null)
}

output "canary_percent_traffic" {
  description = "Share of traffic routed to the canary."
  value       = try(var.canary.percent_traffic, 0)
}
