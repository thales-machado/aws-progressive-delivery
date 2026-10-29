output "rest_api_id" {
  description = "REST API ID, used by the pipeline to create deployments."
  value       = aws_api_gateway_rest_api.this.id
}

output "health_url" {
  description = "Health endpoint. The response shows which version served it and whether it was a canary request."
  value       = "${module.stage.invoke_url}/health"
}

output "canary_percent_traffic" {
  description = "Share of traffic routed to the canary."
  value       = module.stage.canary_percent_traffic
}
