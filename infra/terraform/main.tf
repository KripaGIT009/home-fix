##############################################################
# HomeFix Platform — Root Terraform Module
# Wires together all child modules for the HomeFix AWS infra.
#
# DEPLOYMENT ORDER (two-phase apply required):
#
#   Phase 1 — Core AWS infrastructure (no EKS provider needed):
#     terraform apply -target=module.kms \
#                     -target=module.vpc \
#                     -target=module.security_groups \
#                     -target=module.eks \
#                     -target=module.rds \
#                     -target=module.msk \
#                     -target=module.elasticache \
#                     -target=module.s3 \
#                     -target=module.waf \
#                     -target=module.api_gateway \
#                     -target=module.secrets_manager
#
#   Then capture EKS outputs into tfvars:
#     terraform output -raw eks_cluster_endpoint  → eks_cluster_endpoint
#     terraform output -raw eks_cluster_certificate_authority_data → eks_cluster_ca_data
#     terraform output -raw eks_cluster_name      → eks_cluster_name
#
#   Phase 2 — Kubernetes resources (kubernetes provider now has real endpoint):
#     terraform apply -target=module.iam -target=module.k8s_namespaces
#
#   Or run a full apply after setting eks_cluster_endpoint, eks_cluster_name,
#   and eks_cluster_ca_data variables.
##############################################################

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  common_tags = {
    Project     = "HomeFix"
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}

# ─────────────────────────────────────────────
# Data sources
# ─────────────────────────────────────────────
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

# ─────────────────────────────────────────────
# KMS — must come first (other modules depend on it)
# ─────────────────────────────────────────────
module "kms" {
  source = "./modules/kms"

  name_prefix    = local.name_prefix
  aws_account_id = data.aws_caller_identity.current.account_id
  aws_region     = var.aws_region
  tags           = local.common_tags
}

# ─────────────────────────────────────────────
# VPC & Networking
# ─────────────────────────────────────────────
module "vpc" {
  source = "./modules/vpc"

  name_prefix           = local.name_prefix
  vpc_cidr              = var.vpc_cidr
  availability_zones    = var.availability_zones
  public_subnet_cidrs   = var.public_subnet_cidrs
  private_subnet_cidrs  = var.private_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
  tags                  = local.common_tags
}

# ─────────────────────────────────────────────
# Security Groups
# ─────────────────────────────────────────────
module "security_groups" {
  source = "./modules/security_groups"

  name_prefix         = local.name_prefix
  vpc_id              = module.vpc.vpc_id
  vpc_cidr            = var.vpc_cidr
  allowed_cidr_blocks = var.allowed_cidr_blocks
  tags                = local.common_tags
}

# ─────────────────────────────────────────────
# IAM Roles
# ─────────────────────────────────────────────
module "iam" {
  source = "./modules/iam"

  name_prefix    = local.name_prefix
  aws_account_id = data.aws_caller_identity.current.account_id
  aws_region     = var.aws_region
  aws_partition  = data.aws_partition.current.partition

  # IRSA dependency — EKS OIDC provider set after EKS is created
  eks_oidc_provider_arn = module.eks.oidc_provider_arn
  eks_oidc_provider_url = module.eks.oidc_provider_url

  kms_key_rds_arn = module.kms.key_rds_arn
  kms_key_s3_arn  = module.kms.key_s3_arn
  kms_key_pii_arn = module.kms.key_pii_arn

  s3_bucket_arns = [
    module.s3.bucket_documents_arn,
    module.s3.bucket_photos_arn,
    module.s3.bucket_invoices_arn,
    module.s3.bucket_media_arn,
  ]

  tags = local.common_tags
}

# ─────────────────────────────────────────────
# EKS Cluster (Multi-AZ)
# ─────────────────────────────────────────────
module "eks" {
  source = "./modules/eks"

  name_prefix        = local.name_prefix
  cluster_version    = var.eks_cluster_version
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  node_groups        = var.eks_node_groups
  cluster_sg_id      = module.security_groups.eks_cluster_sg_id
  node_sg_id         = module.security_groups.eks_node_sg_id
  kms_key_arn        = module.kms.key_rds_arn  # reuse for secrets encryption

  tags = local.common_tags
}

# ─────────────────────────────────────────────
# RDS PostgreSQL (Multi-AZ)
# ─────────────────────────────────────────────
module "rds" {
  source = "./modules/rds"

  name_prefix               = local.name_prefix
  vpc_id                    = module.vpc.vpc_id
  database_subnet_ids       = module.vpc.database_subnet_ids
  security_group_id         = module.security_groups.rds_sg_id
  instance_class            = var.rds_instance_class
  engine_version            = var.rds_engine_version
  allocated_storage_gb      = var.rds_allocated_storage_gb
  max_allocated_storage_gb  = var.rds_max_allocated_storage_gb
  db_name                   = var.rds_db_name
  kms_key_arn               = module.kms.key_rds_arn
  log_retention_days        = var.log_retention_days
  tags                      = local.common_tags
}

# ─────────────────────────────────────────────
# Amazon MSK (Kafka)
# ─────────────────────────────────────────────
module "msk" {
  source = "./modules/msk"

  name_prefix         = local.name_prefix
  kafka_version       = var.msk_kafka_version
  broker_instance_type = var.msk_broker_instance_type
  broker_count        = var.msk_broker_count
  broker_storage_gb   = var.msk_broker_storage_gb
  private_subnet_ids  = module.vpc.private_subnet_ids
  security_group_id   = module.security_groups.msk_sg_id
  kms_key_arn         = module.kms.key_s3_arn  # reuse KMS for MSK EBS encryption
  log_retention_days  = var.log_retention_days
  tags                = local.common_tags
}

# ─────────────────────────────────────────────
# ElastiCache Redis (Cluster Mode)
# ─────────────────────────────────────────────
module "elasticache" {
  source = "./modules/elasticache"

  name_prefix          = local.name_prefix
  node_type            = var.redis_node_type
  num_cache_clusters   = var.redis_num_cache_clusters
  engine_version       = var.redis_engine_version
  database_subnet_ids  = module.vpc.database_subnet_ids
  security_group_id    = module.security_groups.redis_sg_id
  kms_key_arn          = module.kms.key_s3_arn
  log_retention_days   = var.log_retention_days
  tags                 = local.common_tags
}

# ─────────────────────────────────────────────
# S3 Buckets
# ─────────────────────────────────────────────
module "s3" {
  source = "./modules/s3"

  name_prefix    = local.name_prefix
  aws_account_id = data.aws_caller_identity.current.account_id
  kms_key_arn    = module.kms.key_s3_arn
  tags           = local.common_tags
}

# ─────────────────────────────────────────────
# WAF (OWASP Top 10)
# ─────────────────────────────────────────────
module "waf" {
  source = "./modules/waf"

  name_prefix = local.name_prefix
  tags        = local.common_tags
}

# ─────────────────────────────────────────────
# API Gateway (HTTP + JWT authorizer)
# ─────────────────────────────────────────────
module "api_gateway" {
  source = "./modules/api_gateway"

  name_prefix         = local.name_prefix
  stage_name          = var.api_gateway_stage_name
  throttle_burst_limit = var.api_gateway_throttle_burst_limit
  throttle_rate_limit  = var.api_gateway_throttle_rate_limit
  waf_web_acl_arn     = module.waf.web_acl_arn
  log_retention_days  = var.log_retention_days
  tags                = local.common_tags
}

# ─────────────────────────────────────────────
# Secrets Manager
# ─────────────────────────────────────────────
module "secrets_manager" {
  source = "./modules/secrets_manager"

  name_prefix      = local.name_prefix
  kms_key_pii_arn  = module.kms.key_pii_arn
  rds_endpoint     = module.rds.db_endpoint
  rds_port         = module.rds.db_port
  rds_db_name      = module.rds.db_name
  tags             = local.common_tags
}

# ─────────────────────────────────────────────
# Kubernetes Namespaces
# ─────────────────────────────────────────────
module "k8s_namespaces" {
  source = "./modules/k8s_namespaces"

  # Explicit dependency on EKS cluster being ready
  depends_on = [module.eks]
}
