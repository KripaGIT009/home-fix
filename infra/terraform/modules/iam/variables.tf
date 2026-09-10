variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
}

variable "aws_account_id" {
  description = "AWS account ID"
  type        = string
}

variable "aws_region" {
  description = "AWS region"
  type        = string
}

variable "aws_partition" {
  description = "AWS partition (aws, aws-cn, aws-us-gov)"
  type        = string
  default     = "aws"
}

variable "eks_oidc_provider_arn" {
  description = "ARN of the EKS OIDC provider for IRSA trust policies"
  type        = string
}

variable "eks_oidc_provider_url" {
  description = "URL of the EKS OIDC provider (without https://)"
  type        = string
}

variable "kms_key_rds_arn" {
  description = "ARN of the RDS KMS key"
  type        = string
}

variable "kms_key_s3_arn" {
  description = "ARN of the S3 KMS key"
  type        = string
}

variable "kms_key_pii_arn" {
  description = "ARN of the PII field-level KMS key"
  type        = string
}

variable "s3_bucket_arns" {
  description = "List of S3 bucket ARNs that services may access"
  type        = list(string)
}

variable "tags" {
  description = "Common resource tags"
  type        = map(string)
  default     = {}
}
