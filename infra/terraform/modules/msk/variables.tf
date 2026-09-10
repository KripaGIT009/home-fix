variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "kafka_version" {
  description = "Apache Kafka version"
  type        = string
}

variable "broker_instance_type" {
  description = "MSK broker instance type"
  type        = string
}

variable "broker_count" {
  description = "Number of MSK broker nodes (must be a multiple of the AZ count)"
  type        = number
}

variable "broker_storage_gb" {
  description = "EBS storage per broker in GB"
  type        = number
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for broker placement (one per AZ)"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID for the MSK brokers"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for MSK at-rest encryption"
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention period in days"
  type        = number
  default     = 90
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
