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

output "deployment_id" {
  description = "Deployment the stage serves as of the last refresh. Moves whenever the release pipeline promotes a release."
  value       = aws_api_gateway_stage.this.deployment_id
}
