output "vpc_id" {
  description = "ID of the HomeFix VPC"
  value       = module.vpc.vpc_id
}

output "private_subnet_ids" {
  description = "Private subnet IDs"
  value       = module.vpc.private_subnet_ids
}

output "public_subnet_ids" {
  description = "Public subnet IDs"
  value       = module.vpc.public_subnet_ids
}

output "database_subnet_ids" {
  description = "Database subnet IDs"
  value       = module.vpc.database_subnet_ids
}

# ─── EKS ─────────────────────────────────────
output "eks_cluster_name" {
  description = "EKS cluster name"
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS cluster API server endpoint"
  value       = module.eks.cluster_endpoint
}

output "eks_cluster_certificate_authority_data" {
  description = "Base64-encoded CA certificate for the EKS cluster"
  value       = module.eks.cluster_certificate_authority_data
  sensitive   = true
}

output "eks_oidc_provider_arn" {
  description = "ARN of the EKS OIDC provider for IRSA"
  value       = module.eks.oidc_provider_arn
}

output "eks_oidc_provider_url" {
  description = "URL of the EKS OIDC provider"
  value       = module.eks.oidc_provider_url
}

# ─── RDS ─────────────────────────────────────
output "rds_endpoint" {
  description = "RDS PostgreSQL writer endpoint"
  value       = module.rds.db_endpoint
  sensitive   = true
}

output "rds_port" {
  description = "RDS PostgreSQL port"
  value       = module.rds.db_port
}

output "rds_db_name" {
  description = "RDS database name"
  value       = module.rds.db_name
}

# ─── MSK ─────────────────────────────────────
output "msk_bootstrap_brokers_tls" {
  description = "MSK TLS bootstrap broker connection string"
  value       = module.msk.bootstrap_brokers_tls
  sensitive   = true
}

output "msk_zookeeper_connect_string" {
  description = "MSK ZooKeeper connection string"
  value       = module.msk.zookeeper_connect_string
  sensitive   = true
}

# ─── ElastiCache Redis ────────────────────────
output "redis_configuration_endpoint" {
  description = "ElastiCache Redis cluster mode configuration endpoint"
  value       = module.elasticache.configuration_endpoint
  sensitive   = true
}

# ─── S3 ──────────────────────────────────────
output "s3_bucket_documents" {
  description = "S3 bucket name for provider documents"
  value       = module.s3.bucket_documents
}

output "s3_bucket_photos" {
  description = "S3 bucket name for job photos"
  value       = module.s3.bucket_photos
}

output "s3_bucket_invoices" {
  description = "S3 bucket name for invoices"
  value       = module.s3.bucket_invoices
}

output "s3_bucket_media" {
  description = "S3 bucket name for general media"
  value       = module.s3.bucket_media
}

# ─── KMS ─────────────────────────────────────
output "kms_key_rds_arn" {
  description = "ARN of the KMS key used for RDS encryption"
  value       = module.kms.key_rds_arn
}

output "kms_key_s3_arn" {
  description = "ARN of the KMS key used for S3 encryption"
  value       = module.kms.key_s3_arn
}

output "kms_key_pii_arn" {
  description = "ARN of the KMS key used for field-level PII encryption"
  value       = module.kms.key_pii_arn
}

# ─── API Gateway ─────────────────────────────
output "api_gateway_invoke_url" {
  description = "API Gateway invocation URL"
  value       = module.api_gateway.invoke_url
}

output "api_gateway_id" {
  description = "API Gateway REST API ID"
  value       = module.api_gateway.api_id
}

# ─── WAF ─────────────────────────────────────
output "waf_web_acl_arn" {
  description = "WAF Web ACL ARN"
  value       = module.waf.web_acl_arn
}

# ─── Secrets Manager ─────────────────────────
output "secrets_manager_arns" {
  description = "Map of Secrets Manager secret ARNs"
  value       = module.secrets_manager.secret_arns
  sensitive   = true
}
