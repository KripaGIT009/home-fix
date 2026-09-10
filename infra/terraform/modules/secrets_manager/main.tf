##############################################################
# Secrets Manager Module — HomeFix
# Placeholder secrets for all 19 microservices.
# Each secret stores a JSON envelope; the actual values are
# rotated/updated by the service CI/CD pipeline or rotation
# Lambda functions — Terraform only creates the secret shells.
##############################################################

locals {
  # Service credential secrets — one per microservice
  service_secrets = {
    "auth-service" = {
      description = "Auth Service credentials: DB connection, JWT signing key, SMS gateway API key"
      placeholder = {
        db_host       = var.rds_endpoint
        db_port       = tostring(var.rds_port)
        db_name       = var.rds_db_name
        db_username   = "auth_svc"
        db_password   = "PLACEHOLDER_ROTATE_ME"
        jwt_secret    = "PLACEHOLDER_ROTATE_ME"
        sms_api_key   = "PLACEHOLDER_ROTATE_ME"
        redis_url     = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "customer-service" = {
      description = "Customer Service credentials: DB connection, KMS key reference"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "customer_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "provider-service" = {
      description = "Provider Service credentials: DB connection"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "provider_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "verification-service" = {
      description = "Verification Service credentials: DB, S3, background-check provider API key"
      placeholder = {
        db_host           = var.rds_endpoint
        db_port           = tostring(var.rds_port)
        db_name           = var.rds_db_name
        db_username       = "verification_svc"
        db_password       = "PLACEHOLDER_ROTATE_ME"
        bgcheck_api_key   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "catalog-service" = {
      description = "Service Catalog credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "catalog_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "pricing-engine" = {
      description = "Pricing Engine credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "pricing_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "booking-service" = {
      description = "Booking Service credentials: DB, Redis, Kafka"
      placeholder = {
        db_host          = var.rds_endpoint
        db_port          = tostring(var.rds_port)
        db_name          = var.rds_db_name
        db_username      = "booking_svc"
        db_password      = "PLACEHOLDER_ROTATE_ME"
        redis_url        = "PLACEHOLDER_ROTATE_ME"
        kafka_brokers    = "PLACEHOLDER_ROTATE_ME"
        kafka_username   = "PLACEHOLDER_ROTATE_ME"
        kafka_password   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "dispatch-engine" = {
      description = "Dispatch Engine credentials: DB, Redis, Kafka"
      placeholder = {
        db_host          = var.rds_endpoint
        db_port          = tostring(var.rds_port)
        db_name          = var.rds_db_name
        db_username      = "dispatch_svc"
        db_password      = "PLACEHOLDER_ROTATE_ME"
        redis_url        = "PLACEHOLDER_ROTATE_ME"
        kafka_brokers    = "PLACEHOLDER_ROTATE_ME"
        kafka_username   = "PLACEHOLDER_ROTATE_ME"
        kafka_password   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "location-service" = {
      description = "Location Service credentials: DB, Redis, Maps API key"
      placeholder = {
        db_host       = var.rds_endpoint
        db_port       = tostring(var.rds_port)
        db_name       = var.rds_db_name
        db_username   = "location_svc"
        db_password   = "PLACEHOLDER_ROTATE_ME"
        redis_url     = "PLACEHOLDER_ROTATE_ME"
        maps_api_key  = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "payment-service" = {
      description = "Payment Service credentials: DB, Redis, Razorpay, Stripe"
      placeholder = {
        db_host              = var.rds_endpoint
        db_port              = tostring(var.rds_port)
        db_name              = var.rds_db_name
        db_username          = "payment_svc"
        db_password          = "PLACEHOLDER_ROTATE_ME"
        redis_url            = "PLACEHOLDER_ROTATE_ME"
        razorpay_key_id      = "PLACEHOLDER_ROTATE_ME"
        razorpay_key_secret  = "PLACEHOLDER_ROTATE_ME"
        stripe_secret_key    = "PLACEHOLDER_ROTATE_ME"
        stripe_webhook_secret = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "invoice-service" = {
      description = "Invoice Service credentials: DB, S3, SES"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "invoice_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "rating-review-service" = {
      description = "Rating Service credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "rating_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "complaint-service" = {
      description = "Complaint Service credentials: DB"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "complaint_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "notification-service" = {
      description = "Notification Service credentials: DB, Twilio, SendGrid, FCM"
      placeholder = {
        db_host           = var.rds_endpoint
        db_port           = tostring(var.rds_port)
        db_name           = var.rds_db_name
        db_username       = "notification_svc"
        db_password       = "PLACEHOLDER_ROTATE_ME"
        twilio_account_sid = "PLACEHOLDER_ROTATE_ME"
        twilio_auth_token  = "PLACEHOLDER_ROTATE_ME"
        sendgrid_api_key   = "PLACEHOLDER_ROTATE_ME"
        fcm_server_key     = "PLACEHOLDER_ROTATE_ME"
        apns_key_id        = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "chat-service" = {
      description = "Chat Service credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "chat_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "admin-service" = {
      description = "Admin Service credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "admin_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "reporting-service" = {
      description = "Reporting Service credentials: DB, analytics store"
      placeholder = {
        db_host               = var.rds_endpoint
        db_port               = tostring(var.rds_port)
        db_name               = var.rds_db_name
        db_username           = "reporting_svc"
        db_password           = "PLACEHOLDER_ROTATE_ME"
        analytics_db_host     = "PLACEHOLDER_ROTATE_ME"
        analytics_db_password = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "promotion-service" = {
      description = "Promotion Service credentials: DB, Redis"
      placeholder = {
        db_host     = var.rds_endpoint
        db_port     = tostring(var.rds_port)
        db_name     = var.rds_db_name
        db_username = "promotion_svc"
        db_password = "PLACEHOLDER_ROTATE_ME"
        redis_url   = "PLACEHOLDER_ROTATE_ME"
      }
    }
    "outbox-processor" = {
      description = "Outbox Processor credentials: DB, Kafka"
      placeholder = {
        db_host          = var.rds_endpoint
        db_port          = tostring(var.rds_port)
        db_name          = var.rds_db_name
        db_username      = "outbox_svc"
        db_password      = "PLACEHOLDER_ROTATE_ME"
        kafka_brokers    = "PLACEHOLDER_ROTATE_ME"
        kafka_username   = "PLACEHOLDER_ROTATE_ME"
        kafka_password   = "PLACEHOLDER_ROTATE_ME"
      }
    }
  }
}

# ─────────────────────────────────────────────
# Secrets Manager Secrets
# ─────────────────────────────────────────────
resource "aws_secretsmanager_secret" "service" {
  for_each = local.service_secrets

  name                    = "${var.name_prefix}/${each.key}"
  description             = each.value.description
  kms_key_id              = var.kms_key_pii_arn
  recovery_window_in_days = 30

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-secret-${each.key}"
    Service = each.key
  })
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each = local.service_secrets

  secret_id     = aws_secretsmanager_secret.service[each.key].id
  secret_string = jsonencode(each.value.placeholder)

  # Ignore future changes — secrets are rotated outside Terraform
  lifecycle {
    ignore_changes = [secret_string]
  }
}
