variable "region" {
  description = "AWS region of the REST API."
  type        = string
}

variable "api_name" {
  description = "Name of the REST API, which is the ApiName dimension of its CloudWatch metrics."
  type        = string
}

variable "stage_name" {
  description = "Name of the stage under release."
  type        = string
}

variable "canary_stage_dimension" {
  description = "Value of the Stage dimension for canary traffic. Defaults to <stage_name>/Canary."
  type        = string
  default     = null
}

variable "name_prefix" {
  description = "Prefix of the alarm names. The pipeline finds and silences alarms by this prefix."
  type        = string
  default     = "aws-progressive-delivery"
}

variable "max_5xx_rate" {
  description = "Canary 5XX rate (0 to 1) above which the alarm fires."
  type        = number
  default     = 0.01

  validation {
    condition     = var.max_5xx_rate >= 0 && var.max_5xx_rate <= 1
    error_message = "max_5xx_rate must be between 0 and 1."
  }
}

variable "min_requests" {
  description = "Requests per period below which the alarms stay quiet, so that a handful of requests cannot trip them."
  type        = number
  default     = 100

  validation {
    condition     = var.min_requests >= 1
    error_message = "min_requests must be at least 1."
  }
}

variable "max_p99_latency_ms" {
  description = "Canary p99 latency in milliseconds above which the latency alarm fires. Null creates no latency alarm."
  type        = number
  default     = null

  validation {
    condition     = var.max_p99_latency_ms == null ? true : var.max_p99_latency_ms > 0
    error_message = "max_p99_latency_ms must be positive."
  }
}

variable "period_seconds" {
  description = "Length of each evaluation period in seconds."
  type        = number
  default     = 60

  validation {
    condition     = contains([10, 30], var.period_seconds) || var.period_seconds % 60 == 0
    error_message = "period_seconds must be 10, 30 or a multiple of 60."
  }
}

variable "evaluation_periods" {
  description = "Number of periods evaluated by each alarm."
  type        = number
  default     = 5

  validation {
    condition     = var.evaluation_periods >= 1
    error_message = "evaluation_periods must be at least 1."
  }
}

variable "datapoints_to_alarm" {
  description = "Number of breaching periods, within evaluation_periods, that fires an alarm."
  type        = number
  default     = 3

  validation {
    condition     = var.datapoints_to_alarm >= 1
    error_message = "datapoints_to_alarm must be at least 1."
  }
}

variable "alarm_actions" {
  description = "ARNs notified when an alarm fires, for example an SNS topic."
  type        = list(string)
  default     = []
}

variable "ok_actions" {
  description = "ARNs notified when an alarm returns to OK."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
