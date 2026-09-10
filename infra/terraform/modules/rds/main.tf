##############################################################
# RDS Module — HomeFix
# Multi-AZ PostgreSQL with parameter group, enhanced monitoring,
# automated backups, and per-service schema creation.
##############################################################

# ─────────────────────────────────────────────
# Random password for RDS master user
# ─────────────────────────────────────────────
resource "random_password" "master" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}:?"
}

# ─────────────────────────────────────────────
# Secrets Manager secret for RDS credentials
# ─────────────────────────────────────────────
resource "aws_secretsmanager_secret" "rds_master" {
  name                    = "${var.name_prefix}/rds/master"
  description             = "RDS PostgreSQL master credentials"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 30

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds-master-secret"
  })
}

resource "aws_secretsmanager_secret_version" "rds_master" {
  secret_id = aws_secretsmanager_secret.rds_master.id
  secret_string = jsonencode({
    username = "homefix_master"
    password = random_password.master.result
    engine   = "postgres"
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
    dbname   = var.db_name
  })
}

# ─────────────────────────────────────────────
# Parameter Group
# ─────────────────────────────────────────────
resource "aws_db_parameter_group" "this" {
  name        = "${var.name_prefix}-pg15"
  family      = "postgres15"
  description = "HomeFix PostgreSQL 15 parameter group"

  # Enable logical replication for the outbox pattern / CDC
  parameter {
    name         = "rds.logical_replication"
    value        = "1"
    apply_method = "pending-reboot"
  }

  # Tune shared_buffers (relative to instance class — value is percentage of RAM)
  parameter {
    name         = "shared_buffers"
    value        = "{DBInstanceClassMemory/32768}"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "log_min_duration_statement"
    value        = "1000"  # Log queries taking longer than 1 second
    apply_method = "immediate"
  }

  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_disconnections"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_lock_waits"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "idle_in_transaction_session_timeout"
    value        = "30000"  # 30 seconds
    apply_method = "immediate"
  }

  parameter {
    name         = "statement_timeout"
    value        = "60000"  # 60 seconds
    apply_method = "immediate"
  }

  tags = var.tags
}

# ─────────────────────────────────────────────
# Option Group
# ─────────────────────────────────────────────
resource "aws_db_option_group" "this" {
  name                 = "${var.name_prefix}-pg15-options"
  option_group_description = "HomeFix PostgreSQL 15 option group"
  engine_name          = "postgres"
  major_engine_version = "15"

  tags = var.tags
}

# ─────────────────────────────────────────────
# Enhanced Monitoring IAM Role
# ─────────────────────────────────────────────
resource "aws_iam_role" "rds_monitoring" {
  name = "${var.name_prefix}-rds-monitoring-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  role       = aws_iam_role.rds_monitoring.name
}

# ─────────────────────────────────────────────
# DB Subnet Group
# ─────────────────────────────────────────────
resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-rds-subnet-group"
  subnet_ids = var.database_subnet_ids

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds-subnet-group"
  })
}

# ─────────────────────────────────────────────
# RDS PostgreSQL Multi-AZ Instance
# ─────────────────────────────────────────────
resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = var.db_name
  username = "homefix_master"
  password = random_password.master.result

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  parameter_group_name   = aws_db_parameter_group.this.name
  option_group_name      = aws_db_option_group.this.name

  multi_az                     = true
  publicly_accessible          = false
  auto_minor_version_upgrade   = true
  allow_major_version_upgrade  = false
  apply_immediately            = false

  backup_retention_period   = 30
  backup_window             = "03:00-04:00"
  maintenance_window        = "sun:04:00-sun:05:00"
  copy_tags_to_snapshot     = true
  deletion_protection       = true
  delete_automated_backups  = false
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-postgres-final-snapshot"

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  monitoring_interval             = 60
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn
  performance_insights_enabled    = true
  performance_insights_retention_period = 731  # 2 years

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-postgres"
  })

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [password]
  }
}

# ─────────────────────────────────────────────
# CloudWatch Alarms for RDS
# ─────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.name_prefix}-rds-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU utilization exceeds 80%"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.id
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.name_prefix}-rds-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 10737418240  # 10 GB in bytes
  alarm_description   = "RDS free storage space below 10 GB"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.id
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${var.name_prefix}-rds-connections-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "DatabaseConnections"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 800
  alarm_description   = "RDS database connections exceeds 800"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.this.id
  }

  tags = var.tags
}
