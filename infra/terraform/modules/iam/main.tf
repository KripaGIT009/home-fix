##############################################################
# IAM Module — HomeFix
# Service-level IAM roles with IRSA (IAM Roles for Service
# Accounts) so each microservice has least-privilege access.
##############################################################

locals {
  # Namespace → list of service-account names that need IRSA roles
  service_accounts = {
    "auth-customer-provider" = [
      "auth-service",
      "customer-service",
      "provider-service",
    ]
    "booking-dispatch-location" = [
      "booking-service",
      "dispatch-engine",
      "location-service",
    ]
    "payment-invoice-rating" = [
      "payment-service",
      "invoice-service",
      "rating-review-service",
    ]
    "notification-chat-complaint" = [
      "notification-service",
      "chat-service",
      "complaint-service",
    ]
    "admin-reporting-promotion" = [
      "admin-service",
      "reporting-service",
      "promotion-service",
    ]
    "catalog-pricing-verification" = [
      "catalog-service",
      "pricing-engine",
      "verification-service",
      "outbox-processor",
    ]
  }

  # Flatten to a map keyed by "<namespace>/<service-account>"
  flat_service_accounts = merge([
    for ns, sas in local.service_accounts : {
      for sa in sas : "${ns}/${sa}" => {
        namespace       = ns
        service_account = sa
      }
    }
  ]...)
}

# ─────────────────────────────────────────────
# IRSA Role per Microservice
# ─────────────────────────────────────────────
resource "aws_iam_role" "service" {
  for_each = local.flat_service_accounts

  name = "${var.name_prefix}-irsa-${each.value.service_account}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = var.eks_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${var.eks_oidc_provider_url}:sub" = "system:serviceaccount:${each.value.namespace}:${each.value.service_account}"
          "${var.eks_oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = merge(var.tags, {
    Name             = "${var.name_prefix}-irsa-${each.value.service_account}"
    Namespace        = each.value.namespace
    ServiceAccount   = each.value.service_account
  })
}

# ─────────────────────────────────────────────
# Secrets Manager read policy (all services need this)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "secrets_read" {
  name        = "${var.name_prefix}-secrets-read"
  description = "Allow reading HomeFix secrets from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
        ]
        Resource = "arn:${var.aws_partition}:secretsmanager:${var.aws_region}:${var.aws_account_id}:secret:${var.name_prefix}/*"
      },
      {
        Sid    = "KmsDecryptSecrets"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = var.kms_key_pii_arn
      }
    ]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "secrets_read" {
  for_each = local.flat_service_accounts

  policy_arn = aws_iam_policy.secrets_read.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# S3 policy (documents, photos, invoices, media)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "s3_rw" {
  name        = "${var.name_prefix}-s3-rw"
  description = "Read/write access to HomeFix S3 buckets"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ObjectAccess"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:GetObjectVersion",
        ]
        Resource = [for arn in var.s3_bucket_arns : "${arn}/*"]
      },
      {
        Sid    = "S3ListBuckets"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = var.s3_bucket_arns
      },
      {
        Sid    = "KmsS3"
        Effect = "Allow"
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt",
          "kms:DescribeKey",
        ]
        Resource = var.kms_key_s3_arn
      }
    ]
  })

  tags = var.tags
}

# Only services that touch S3 get this policy
locals {
  s3_services = toset([
    "auth-customer-provider/customer-service",
    "auth-customer-provider/provider-service",
    "booking-dispatch-location/booking-service",
    "payment-invoice-rating/invoice-service",
    "payment-invoice-rating/rating-review-service",
    "notification-chat-complaint/complaint-service",
    "catalog-pricing-verification/verification-service",
  ])
}

resource "aws_iam_role_policy_attachment" "s3_rw" {
  for_each = local.s3_services

  policy_arn = aws_iam_policy.s3_rw.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# PII KMS policy (services that encrypt/decrypt PII)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "kms_pii" {
  name        = "${var.name_prefix}-kms-pii"
  description = "Encrypt/decrypt PII fields using KMS"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "KmsPii"
      Effect = "Allow"
      Action = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]
      Resource = var.kms_key_pii_arn
    }]
  })

  tags = var.tags
}

locals {
  pii_services = toset([
    "auth-customer-provider/customer-service",
    "auth-customer-provider/provider-service",
    "payment-invoice-rating/payment-service",
    "catalog-pricing-verification/verification-service",
  ])
}

resource "aws_iam_role_policy_attachment" "kms_pii" {
  for_each = local.pii_services

  policy_arn = aws_iam_policy.kms_pii.arn
  role       = aws_iam_role.service[each.key].name
}

# ─────────────────────────────────────────────
# RDS KMS policy (all services that hit the DB)
# ─────────────────────────────────────────────
resource "aws_iam_policy" "kms_rds" {
  name        = "${var.name_prefix}-kms-rds"
  description = "Allow services to use the RDS KMS key"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "KmsRds"
      Effect = "Allow"
      Action = [
        "kms:Decrypt",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]
      Resource = var.kms_key_rds_arn
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "kms_rds" {
  for_each = local.flat_service_accounts

  policy_arn = aws_iam_policy.kms_rds.arn
  role       = aws_iam_role.service[each.key].name
}
