variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "kms_key_pii_arn" {
  description = "KMS key ARN used to encrypt all secrets"
  type        = string
}

variable "rds_endpoint" {
  description = "RDS endpoint hostname to embed in service secret placeholders"
  type        = string
}

variable "rds_port" {
  description = "RDS port to embed in service secret placeholders"
  type        = number
}

variable "rds_db_name" {
  description = "RDS database name to embed in service secret placeholders"
  type        = string
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
