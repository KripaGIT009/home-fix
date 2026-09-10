##############################################################
# MSK Module — HomeFix
# Amazon MSK (Kafka) cluster with TLS, KMS encryption,
# CloudWatch logging, and all required application topics.
##############################################################

locals {
  # All Kafka topics required by HomeFix microservices
  # Each topic also gets a dead-letter queue (DLQ) topic
  application_topics = [
    "booking-created",
    "booking-state-changed",
    "provider-accepted",
    "provider-arriving",
    "provider-arrived",
    "job-started",
    "job-completed",
    "payment-completed",
    "payment-failed",
    "review-submitted",
    "complaint-created",
    "complaint-updated",
    "notification-dispatch",
    "invoice-generated",
    "provider-location-update",
    "verification-state-changed",
    "outbox-events",
  ]

  dlq_topics = [for t in local.application_topics : "${t}.dlq"]
  all_topics = concat(local.application_topics, local.dlq_topics)
}

# ─────────────────────────────────────────────
# CloudWatch Log Group for MSK broker logs
# ─────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${var.name_prefix}/broker"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

# ─────────────────────────────────────────────
# MSK Configuration
# ─────────────────────────────────────────────
resource "aws_msk_configuration" "this" {
  name              = "${var.name_prefix}-msk-config"
  kafka_versions    = [var.kafka_version]
  description       = "HomeFix MSK Kafka broker configuration"

  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=6
    log.retention.hours=168
    log.segment.bytes=536870912
    log.retention.bytes=10737418240
    offsets.topic.replication.factor=3
    transaction.state.log.replication.factor=3
    transaction.state.log.min.isr=2
    delete.topic.enable=true
    compression.type=lz4
    message.max.bytes=5242880
  EOT
}

# ─────────────────────────────────────────────
# MSK Cluster
# ─────────────────────────────────────────────
resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [var.security_group_id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_storage_gb

        provisioned_throughput {
          enabled           = true
          volume_throughput = 250
        }
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  # Encryption in transit — TLS only
  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = var.kms_key_arn
  }

  # SASL/SCRAM for client authentication
  client_authentication {
    sasl {
      scram = true
    }
  }

  # Enhanced monitoring
  enhanced_monitoring = "PER_TOPIC_PER_BROKER"

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
      s3 {
        enabled = false
      }
      firehose {
        enabled = false
      }
    }
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-kafka"
  })
}

# ─────────────────────────────────────────────
# Kafka Topics via aws_msk_serverless is not applicable here;
# Topics are created via a Kubernetes Job post-cluster provisioning.
# We output bootstrap brokers so the Job can connect.
# ─────────────────────────────────────────────

# Store MSK SASL/SCRAM credentials in Secrets Manager
resource "random_password" "msk_scram" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}:?"
}

resource "aws_secretsmanager_secret" "msk_scram" {
  # MSK SCRAM secrets MUST have names prefixed with "AmazonMSK_"
  name                    = "AmazonMSK_${var.name_prefix}-kafka-client"
  description             = "SASL/SCRAM credentials for MSK client authentication"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 30

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-msk-scram-secret"
  })
}

resource "aws_secretsmanager_secret_version" "msk_scram" {
  secret_id = aws_secretsmanager_secret.msk_scram.id
  secret_string = jsonencode({
    username = "homefix-kafka-client"
    password = random_password.msk_scram.result
  })
}

resource "aws_msk_scram_secret_association" "this" {
  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = [aws_secretsmanager_secret.msk_scram.arn]

  depends_on = [aws_secretsmanager_secret_version.msk_scram]
}

# ─────────────────────────────────────────────
# CloudWatch alarms
# ─────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "msk_cpu" {
  alarm_name          = "${var.name_prefix}-msk-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CpuUser"
  namespace           = "AWS/Kafka"
  period              = 300
  statistic           = "Average"
  threshold           = 70
  alarm_description   = "MSK broker CPU utilization exceeds 70%"

  dimensions = {
    Cluster_Name = aws_msk_cluster.this.cluster_name
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "msk_disk" {
  alarm_name          = "${var.name_prefix}-msk-disk-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "KafkaDataLogsDiskUsed"
  namespace           = "AWS/Kafka"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "MSK broker disk usage exceeds 80%"

  dimensions = {
    Cluster_Name = aws_msk_cluster.this.cluster_name
  }

  tags = var.tags
}
