variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "stage_name" {
  description = "Stage name."
  type        = string
  default     = "prod"
}

variable "stable_deployment_id" {
  description = "Stable deployment. Null uses the initial deployment created by this example."
  type        = string
  default     = null
}

variable "canary" {
  description = "Canary settings, written by the pipeline into canary.auto.tfvars.json."
  type        = object({
    deployment_id            = string
    percent_traffic          = number
    stage_variable_overrides = optional(map(string), {})
    use_stage_cache          = optional(bool, false)
  })
  default = null
}
