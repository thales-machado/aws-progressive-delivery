output "api_name" {
  description = "Value for the API_NAMES parameter of the release pipeline."
  value       = aws_api_gateway_rest_api.this.name
}

output "rest_api_id" {
  description = "REST API ID."
  value       = aws_api_gateway_rest_api.this.id
}

output "stage_name" {
  description = "Value for the STAGE_NAME parameter of the release pipeline."
  value       = module.stage.stage_name
}

output "health_url" {
  description = "Health endpoint. The response shows which version served the request and whether it was a canary request."
  value       = "${module.stage.invoke_url}/health"
}
