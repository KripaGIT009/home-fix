variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "node_type" {
  description = "ElastiCache node type (e.g. cache.r6g.large)"
  type        = string
}

variable "num_cache_clusters" {
  description = "Number of replica nodes per shard in cluster mode (e.g. 1 = 1 replica + 1 primary per shard). Minimum 1 for HA."
  type        = number

  validation {
    condition     = var.num_cache_clusters >= 1
    error_message = "num_cache_clusters must be at least 1 (minimum 1 replica per shard for HA)."
  }
}

variable "engine_version" {
  description = "Redis engine version"
  type        = string
}

variable "database_subnet_ids" {
  description = "Subnet IDs for the ElastiCache subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID for the ElastiCache cluster"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for at-rest encryption"
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
