variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "api_name" {
  description = "REST API name. The pipeline selects APIs by this name, so use a different one per copy of this example."
  type        = string
  default     = "pd-example-orders"
}

variable "stage_name" {
  description = "Stage name."
  type        = string
  default     = "prod"
}

variable "version_label" {
  description = "Value of the version stage variable that the health endpoint returns. After promoting a release that overrides it, set this to the promoted value."
  type        = string
  default     = "v1"
}
