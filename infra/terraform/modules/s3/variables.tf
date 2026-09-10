variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "aws_account_id" {
  description = "AWS account ID (appended to bucket names for global uniqueness)"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for SSE-KMS bucket encryption"
  type        = string
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
