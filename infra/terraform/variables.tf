variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name (staging, production)"
  type        = string
  default     = "production"

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "Environment must be 'staging' or 'production'."
  }
}

variable "project_name" {
  description = "Project name used as prefix for resource naming"
  type        = string
  default     = "homefix"
}

# ─────────────────────────────────────────────
# VPC
# ─────────────────────────────────────────────
variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of availability zones (min 3 for Multi-AZ)"
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (one per AZ)"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets (one per AZ)"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
}

variable "database_subnet_cidrs" {
  description = "CIDR blocks for database subnets (one per AZ)"
  type        = list(string)
  default     = ["10.0.21.0/24", "10.0.22.0/24", "10.0.23.0/24"]
}

# ─────────────────────────────────────────────
# EKS
# ─────────────────────────────────────────────
variable "eks_cluster_version" {
  description = "Kubernetes version for EKS cluster"
  type        = string
  default     = "1.29"
}

variable "eks_node_groups" {
  description = "EKS managed node group configurations"
  type = map(object({
    instance_types = list(string)
    min_size       = number
    max_size       = number
    desired_size   = number
    disk_size_gb   = number
    labels         = map(string)
    taints = list(object({
      key    = string
      value  = string
      effect = string
    }))
  }))
  default = {
    general = {
      instance_types = ["t3.xlarge"]
      min_size       = 3
      max_size       = 12
      desired_size   = 3
      disk_size_gb   = 50
      labels = {
        "node-type" = "general"
      }
      taints = []
    }
    compute = {
      instance_types = ["c5.2xlarge"]
      min_size       = 2
      max_size       = 10
      desired_size   = 2
      disk_size_gb   = 50
      labels = {
        "node-type" = "compute"
      }
      taints = []
    }
  }
}

# ─────────────────────────────────────────────
# RDS PostgreSQL
# ─────────────────────────────────────────────
variable "rds_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.r6g.xlarge"
}

variable "rds_allocated_storage_gb" {
  description = "Initial allocated storage for RDS in GB"
  type        = number
  default     = 100
}

variable "rds_max_allocated_storage_gb" {
  description = "Maximum allocated storage for RDS autoscaling in GB"
  type        = number
  default     = 500
}

variable "rds_engine_version" {
  description = "PostgreSQL engine version"
  type        = string
  default     = "15.5"
}

variable "rds_db_name" {
  description = "Initial database name"
  type        = string
  default     = "homefix"
}

# ─────────────────────────────────────────────
# MSK (Kafka)
# ─────────────────────────────────────────────
variable "msk_kafka_version" {
  description = "Apache Kafka version for MSK"
  type        = string
  default     = "3.6.0"
}

variable "msk_broker_instance_type" {
  description = "MSK broker instance type"
  type        = string
  default     = "kafka.m5.xlarge"
}

variable "msk_broker_count" {
  description = "Number of MSK brokers (must be a multiple of AZ count)"
  type        = number
  default     = 3
}

variable "msk_broker_storage_gb" {
  description = "Storage per MSK broker in GB"
  type        = number
  default     = 100
}

# ─────────────────────────────────────────────
# ElastiCache Redis
# ─────────────────────────────────────────────
variable "redis_node_type" {
  description = "ElastiCache Redis node type"
  type        = string
  default     = "cache.r6g.large"
}

variable "redis_num_cache_clusters" {
  description = "Number of replica nodes per shard in Redis cluster mode (minimum 1 for HA)"
  type        = number
  default     = 1
}

variable "redis_engine_version" {
  description = "Redis engine version"
  type        = string
  default     = "7.1"
}

# ─────────────────────────────────────────────
# API Gateway
# ─────────────────────────────────────────────
variable "api_gateway_stage_name" {
  description = "API Gateway stage name"
  type        = string
  default     = "v1"
}

variable "api_gateway_throttle_burst_limit" {
  description = "API Gateway throttle burst limit"
  type        = number
  default     = 5000
}

variable "api_gateway_throttle_rate_limit" {
  description = "API Gateway throttle rate limit (requests/second)"
  type        = number
  default     = 2000
}

# ─────────────────────────────────────────────
# Misc
# ─────────────────────────────────────────────
variable "allowed_cidr_blocks" {
  description = "CIDR blocks allowed to access management interfaces"
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  description = "CloudWatch log retention period in days"
  type        = number
  default     = 90
}

# ─────────────────────────────────────────────
# EKS Provider Configuration
# These are populated after the first `terraform apply` that creates the EKS cluster.
# Run: terraform output eks_cluster_endpoint  etc. and pass via -var or terraform.tfvars
# ─────────────────────────────────────────────
variable "eks_cluster_endpoint" {
  description = "EKS cluster API server endpoint (used to configure kubernetes/helm providers)"
  type        = string
  default     = ""
}

variable "eks_cluster_name" {
  description = "EKS cluster name (used to configure kubernetes/helm providers)"
  type        = string
  default     = ""
}

variable "eks_cluster_ca_data" {
  description = "Base64-encoded EKS cluster CA certificate data (used to configure kubernetes/helm providers)"
  type        = string
  default     = ""
  sensitive   = true
}
