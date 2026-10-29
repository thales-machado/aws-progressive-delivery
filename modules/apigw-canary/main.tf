locals {
  default_access_log_format = jsonencode({
    requestId       = "$context.requestId"
    requestTime     = "$context.requestTime"
    sourceIp        = "$context.identity.sourceIp"
    httpMethod      = "$context.httpMethod"
    resourcePath    = "$context.resourcePath"
    status          = "$context.status"
    responseLatency = "$context.responseLatency"
    isCanary        = "$context.isCanaryRequest"
  })
}

resource "aws_api_gateway_stage" "this" {
  rest_api_id           = var.rest_api_id
  stage_name            = var.stage_name
  deployment_id         = var.initial_deployment_id
  description           = var.description
  variables             = var.stage_variables
  xray_tracing_enabled  = var.xray_tracing_enabled
  cache_cluster_enabled = var.cache_cluster_enabled
  cache_cluster_size    = var.cache_cluster_size
  tags                  = var.tags

  dynamic "access_log_settings" {
    for_each = var.access_log == null ? [] : [var.access_log]

    content {
      destination_arn = access_log_settings.value.destination_arn
      format          = coalesce(access_log_settings.value.format, local.default_access_log_format)
    }
  }

  lifecycle {
    # The release pipeline moves these two pointers through the API Gateway API.
    # Terraform must not revert a release that is in flight or already promoted.
    ignore_changes = [deployment_id, canary_settings]
  }
}

resource "aws_api_gateway_method_settings" "all" {
  count = var.detailed_metrics_enabled ? 1 : 0

  rest_api_id = var.rest_api_id
  stage_name  = aws_api_gateway_stage.this.stage_name
  method_path = "*/*"

  settings {
    metrics_enabled = true
  }
}
