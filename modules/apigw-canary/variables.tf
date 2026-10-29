variable "rest_api_id" {
  description = "ID of the REST API that owns the stage."
  type        = string
}

variable "stage_name" {
  description = "Name of the stage managed by this module."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_]+$", var.stage_name))
    error_message = "stage_name may contain only letters, digits and underscores."
  }
}

variable "initial_deployment_id" {
  description = "Deployment the stage points to when it is created. After that, the release pipeline owns deployment_id and canary_settings, and Terraform ignores both."
  type        = string
}

variable "description" {
  description = "Stage description."
  type        = string
  default     = null
}

variable "stage_variables" {
  description = "Stage variables. A promoted release writes its stageVariableOverrides here, so update this map in Git after promoting to avoid drift."
  type        = map(string)
  default     = {}
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

variable "detailed_metrics_enabled" {
  description = "Publish per-method CloudWatch metrics for every method on the stage. Adds CloudWatch cost."
  type        = bool
  default     = false
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
