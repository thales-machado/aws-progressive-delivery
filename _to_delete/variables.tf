variable "rest_api_id" {
  description = "ID of the REST API that owns the stage."
  type        = string
}

variable "stage_name" {
  description = "Name of the stage managed by this module."
  type        = string
}

variable "stable_deployment_id" {
  description = "Deployment that serves the stable share of the traffic."
  type        = string
}

variable "canary" {
  description = "Canary release settings. Null removes the canary and sends 100% of the traffic to stable_deployment_id."
  type        = object({
    deployment_id            = string
    percent_traffic          = number
    stage_variable_overrides = optional(map(string), {})
    use_stage_cache          = optional(bool, false)
  })
  default = null

  validation {
    condition     = var.canary == null ? true : (var.canary.percent_traffic >= 0 && var.canary.percent_traffic <= 100)
    error_message = "canary.percent_traffic must be between 0 and 100."
  }
}

variable "stage_variables" {
  description = "Stage variables shared by stable and canary, unless overridden in canary.stage_variable_overrides."
  type        = map(string)
  default     = {}
}

variable "description" {
  description = "Stage description."
  type        = string
  default     = null
}

variable "xray_tracing_enabled" {
  description = "Enable AWS X-Ray tracing on the stage."
  type        = bool
  default     = false
}

variable "cache_cluster_enabled" {
  description = "Enable the stage cache cluster."
  type        = bool
  default     = false
}

variable "cache_cluster_size" {
  description = "Size of the stage cache cluster, when enabled."
  type        = string
  default     = null
}

variable "access_log" {
  description = "Access log destination (CloudWatch Logs group or Firehose ARN). The default format records whether each request hit the canary."
  type        = object({
    destination_arn = string
    format          = optional(string)
  })
  default = null
}

variable "tags" {
  description = "Tags applied to the stage."
  type        = map(string)
  default     = {}
}
