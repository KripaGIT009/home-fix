variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}

variable "rate_limit_per_5min" {
  description = "Maximum requests per IP per 5-minute window before rate limiting kicks in"
  type        = number
  default     = 2000
}

variable "block_country_codes" {
  description = "Optional list of ISO 3166-1 alpha-2 country codes to block"
  type        = list(string)
  default     = []
}
