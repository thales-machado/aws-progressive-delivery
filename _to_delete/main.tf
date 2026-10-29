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
  deployment_id         = var.stable_deployment_id
  description           = var.description
  variables             = var.stage_variables
  xray_tracing_enabled  = var.xray_tracing_enabled
  cache_cluster_enabled = var.cache_cluster_enabled
  cache_cluster_size    = var.cache_cluster_size
  tags                  = var.tags

  dynamic "canary_settings" {
    for_each = var.canary == null ? [] : [var.canary]

    content {
      deployment_id            = canary_settings.value.deployment_id
      percent_traffic          = canary_settings.value.percent_traffic
      stage_variable_overrides = canary_settings.value.stage_variable_overrides
      use_stage_cache          = canary_settings.value.use_stage_cache
    }
  }

  dynamic "access_log_settings" {
    for_each = var.access_log == null ? [] : [var.access_log]

    content {
      destination_arn = access_log_settings.value.destination_arn
      format          = coalesce(access_log_settings.value.format, local.default_access_log_format)
    }
  }
}
