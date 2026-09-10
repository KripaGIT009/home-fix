##############################################################
# KMS Module — HomeFix
# Three CMKs: RDS encryption, S3 encryption, PII field-level.
##############################################################

data "aws_iam_policy_document" "kms_base" {
  # Allow root account full control
  statement {
    sid    = "EnableRootAccess"
    effect = "Allow"
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.aws_account_id}:root"]
    }
    actions   = ["kms:*"]
    resources = ["*"]
  }

  # Allow CloudWatch Logs to use the key
  statement {
    sid    = "AllowCloudWatchLogs"
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }
    actions = [
      "kms:Encrypt",
      "kms:Decrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey",
    ]
    resources = ["*"]
  }
}

# ─────────────────────────────────────────────
# KMS Key — RDS Encryption
# ─────────────────────────────────────────────
resource "aws_kms_key" "rds" {
  description              = "HomeFix RDS PostgreSQL encryption key"
  deletion_window_in_days  = 30
  enable_key_rotation      = true
  multi_region             = false
  policy                   = data.aws_iam_policy_document.kms_base.json

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-kms-rds"
    Purpose = "RDS Encryption"
  })
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${var.name_prefix}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

# ─────────────────────────────────────────────
# KMS Key — S3 / MSK / ElastiCache Encryption
# ─────────────────────────────────────────────
resource "aws_kms_key" "s3" {
  description              = "HomeFix S3 / object storage encryption key"
  deletion_window_in_days  = 30
  enable_key_rotation      = true
  multi_region             = false
  policy                   = data.aws_iam_policy_document.kms_base.json

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-kms-s3"
    Purpose = "S3 / Object Storage Encryption"
  })
}

resource "aws_kms_alias" "s3" {
  name          = "alias/${var.name_prefix}-s3"
  target_key_id = aws_kms_key.s3.key_id
}

# ─────────────────────────────────────────────
# KMS Key — Field-Level PII Encryption
# Used by services to encrypt PII columns (email, address, bank account)
# ─────────────────────────────────────────────
resource "aws_kms_key" "pii" {
  description              = "HomeFix field-level PII encryption key (AES-256)"
  deletion_window_in_days  = 30
  enable_key_rotation      = true
  multi_region             = false
  policy                   = data.aws_iam_policy_document.kms_base.json

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-kms-pii"
    Purpose = "PII Field-Level Encryption"
  })
}

resource "aws_kms_alias" "pii" {
  name          = "alias/${var.name_prefix}-pii"
  target_key_id = aws_kms_key.pii.key_id
}
