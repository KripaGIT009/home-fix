variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "stage_name" {
  description = "API Gateway stage name (e.g. v1)"
  type        = string
  default     = "v1"
}

variable "jwt_issuer_url" {
  description = "JWT issuer URL — should match the Auth Service token issuer claim"
  type        = string
  default     = "https://auth.homefix.internal"
}

variable "throttle_burst_limit" {
  description = "API Gateway throttle burst limit (max concurrent requests)"
  type        = number
  default     = 5000
}

variable "throttle_rate_limit" {
  description = "API Gateway steady-state rate limit (requests/second)"
  type        = number
  default     = 2000
}

variable "waf_web_acl_arn" {
  description = "ARN of the WAF Web ACL to associate with the API Gateway stage"
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
  default     = 90
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
