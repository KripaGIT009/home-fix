variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "cluster_version" {
  description = "Kubernetes version"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for node groups"
  type        = list(string)
}

variable "cluster_sg_id" {
  description = "Security group ID for the EKS cluster control plane"
  type        = string
}

variable "node_sg_id" {
  description = "Security group ID for EKS worker nodes"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for Kubernetes secrets encryption"
  type        = string
}

variable "node_groups" {
  description = "Map of node group configurations"
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
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
