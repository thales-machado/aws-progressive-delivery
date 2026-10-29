provider "aws" {
  region = var.region

  default_tags {
    tags = var.tags
  }
}

locals {
  alarm_prefix = "${var.name_prefix}-${var.api_name}-${var.stage_name}"
  canary_stage = coalesce(var.canary_stage_dimension, "${var.stage_name}/Canary")
  dimensions   = { ApiName = var.api_name, Stage = local.canary_stage }
}

resource "aws_cloudwatch_metric_alarm" "error_rate" {
  alarm_name          = "${local.alarm_prefix}-5xx-rate"
  alarm_description   = "Canary 5XX rate above ${var.max_5xx_rate} while serving at least ${var.min_requests} requests per period."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.max_5xx_rate
  evaluation_periods  = var.evaluation_periods
  datapoints_to_alarm = var.datapoints_to_alarm
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.ok_actions

  metric_query {
    id          = "requests"
    return_data = false

    metric {
      namespace   = "AWS/ApiGateway"
      metric_name = "Count"
      period      = var.period_seconds
      stat        = "SampleCount"
      dimensions  = local.dimensions
    }
  }

  metric_query {
    id          = "errors"
    return_data = false

    metric {
      namespace   = "AWS/ApiGateway"
      metric_name = "5XXError"
      period      = var.period_seconds
      stat        = "Sum"
      dimensions  = local.dimensions
    }
  }

  metric_query {
    id          = "rate"
    label       = "Canary 5XX rate"
    expression  = "IF(requests >= ${var.min_requests}, errors / requests, 0)"
    return_data = true
  }

  lifecycle {
    precondition {
      condition     = var.datapoints_to_alarm <= var.evaluation_periods
      error_message = "datapoints_to_alarm cannot exceed evaluation_periods."
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "p99_latency" {
  count = var.max_p99_latency_ms == null ? 0 : 1

  alarm_name          = "${local.alarm_prefix}-p99-latency"
  alarm_description   = "Canary p99 latency above ${var.max_p99_latency_ms} ms while serving at least ${var.min_requests} requests per period."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.max_p99_latency_ms
  evaluation_periods  = var.evaluation_periods
  datapoints_to_alarm = var.datapoints_to_alarm
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.ok_actions

  metric_query {
    id          = "requests"
    return_data = false

    metric {
      namespace   = "AWS/ApiGateway"
      metric_name = "Count"
      period      = var.period_seconds
      stat        = "SampleCount"
      dimensions  = local.dimensions
    }
  }

  metric_query {
    id          = "latency"
    return_data = false

    metric {
      namespace   = "AWS/ApiGateway"
      metric_name = "Latency"
      period      = var.period_seconds
      stat        = "p99"
      dimensions  = local.dimensions
    }
  }

  metric_query {
    id          = "gated_latency"
    label       = "Canary p99 latency"
    expression  = "IF(requests >= ${var.min_requests}, latency, 0)"
    return_data = true
  }
}
