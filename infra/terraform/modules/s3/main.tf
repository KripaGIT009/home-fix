##############################################################
# S3 Module — HomeFix
# Four encrypted buckets: documents, photos, invoices, media.
# All buckets: versioning, SSE-KMS, lifecycle rules,
# public access block, and access logging.
##############################################################

locals {
  buckets = {
    documents = {
      suffix      = "documents"
      description = "Provider verification documents"
      lifecycle_days_ia       = 30
      lifecycle_days_glacier  = 90
      lifecycle_days_expire   = 2555  # 7 years for compliance
    }
    photos = {
      suffix      = "photos"
      description = "Job before/after photos uploaded by providers"
      lifecycle_days_ia       = 60
      lifecycle_days_glacier  = 180
      lifecycle_days_expire   = 1095  # 3 years
    }
    invoices = {
      suffix      = "invoices"
      description = "Generated PDF invoices"
      lifecycle_days_ia       = 30
      lifecycle_days_glacier  = 365
      lifecycle_days_expire   = 2555  # 7 years for accounting compliance
    }
    media = {
      suffix      = "media"
      description = "General media: profile photos, category icons, chat attachments"
      lifecycle_days_ia       = 90
      lifecycle_days_glacier  = 365
      lifecycle_days_expire   = 1095  # 3 years
    }
  }
}

# ─────────────────────────────────────────────
# Access Logs Bucket (central)
# ─────────────────────────────────────────────
resource "aws_s3_bucket" "access_logs" {
  bucket        = "${var.name_prefix}-s3-access-logs-${var.aws_account_id}"
  force_destroy = false

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-s3-access-logs"
    Purpose = "S3 Server Access Logging"
  })
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-logs"
    status = "Enabled"
    expiration {
      days = 90
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# ─────────────────────────────────────────────
# Application Buckets
# ─────────────────────────────────────────────
resource "aws_s3_bucket" "app" {
  for_each = local.buckets

  bucket        = "${var.name_prefix}-${each.value.suffix}-${var.aws_account_id}"
  force_destroy = false

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-${each.value.suffix}"
    Purpose = each.value.description
  })
}

resource "aws_s3_bucket_public_access_block" "app" {
  for_each = local.buckets

  bucket = aws_s3_bucket.app[each.key].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "app" {
  for_each = local.buckets

  bucket = aws_s3_bucket.app[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "app" {
  for_each = local.buckets

  bucket = aws_s3_bucket.app[each.key].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "app" {
  for_each = local.buckets

  bucket = aws_s3_bucket.app[each.key].id

  rule {
    id     = "tiered-storage"
    status = "Enabled"

    transition {
      days          = each.value.lifecycle_days_ia
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = each.value.lifecycle_days_glacier
      storage_class = "GLACIER"
    }

    expiration {
      days = each.value.lifecycle_days_expire
    }

    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "STANDARD_IA"
    }

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_logging" "app" {
  for_each = local.buckets

  bucket        = aws_s3_bucket.app[each.key].id
  target_bucket = aws_s3_bucket.access_logs.id
  target_prefix = "${each.value.suffix}/"
}

# ─────────────────────────────────────────────
# Bucket Policies — enforce HTTPS only
# ─────────────────────────────────────────────
resource "aws_s3_bucket_policy" "app" {
  for_each = local.buckets

  bucket = aws_s3_bucket.app[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyNonTLS"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.app[each.key].arn,
          "${aws_s3_bucket.app[each.key].arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      },
      {
        Sid       = "DenyNonKmsEncryption"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.app[each.key].arn}/*"
        Condition = {
          StringNotEqualsIfExists = {
            "s3:x-amz-server-side-encryption" = "aws:kms"
          }
        }
      }
    ]
  })
}
