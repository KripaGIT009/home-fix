variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "database_subnet_ids" {
  description = "Subnet IDs for the RDS subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID to attach to the RDS instance"
  type        = string
}

variable "instance_class" {
  description = "RDS instance class (e.g. db.r6g.xlarge)"
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL engine version"
  type        = string
}

variable "allocated_storage_gb" {
  description = "Initial allocated storage in GB"
  type        = number
}

variable "max_allocated_storage_gb" {
  description = "Maximum autoscaling storage in GB"
  type        = number
}

variable "db_name" {
  description = "Initial database name"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for storage encryption"
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
