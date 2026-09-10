##############################################################
# ElastiCache Redis Module — HomeFix
# Redis replication group with cluster mode, TLS, auth token,
# at-rest encryption, and automatic failover.
##############################################################

# ─────────────────────────────────────────────
# Auth token stored in Secrets Manager
# ─────────────────────────────────────────────
resource "random_password" "redis_auth" {
  length           = 32
  special          = false  # Redis auth token cannot contain @, :, /
  min_upper        = 4
  min_lower        = 4
  min_numeric      = 4
}

resource "aws_secretsmanager_secret" "redis_auth" {
  name                    = "${var.name_prefix}/redis/auth-token"
  description             = "ElastiCache Redis authentication token"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 30

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-auth-secret"
  })
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id = aws_secretsmanager_secret.redis_auth.id
  secret_string = jsonencode({
    auth_token = random_password.redis_auth.result
  })
}

# ─────────────────────────────────────────────
# Subnet Group
# ─────────────────────────────────────────────
resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis-subnet-group"
  subnet_ids = var.database_subnet_ids

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-subnet-group"
  })
}

# ─────────────────────────────────────────────
# Parameter Group — Redis 7.x cluster mode
# ─────────────────────────────────────────────
resource "aws_elasticache_parameter_group" "this" {
  name        = "${var.name_prefix}-redis7-cluster"
  family      = "redis7"
  description = "HomeFix Redis 7 cluster-mode parameter group"

  parameter {
    name  = "cluster-enabled"
    value = "yes"
  }

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }

  parameter {
    name  = "timeout"
    value = "300"
  }

  parameter {
    name  = "tcp-keepalive"
    value = "60"
  }

  parameter {
    name  = "lazyfree-lazy-eviction"
    value = "yes"
  }

  parameter {
    name  = "lazyfree-lazy-expire"
    value = "yes"
  }

  tags = var.tags
}

# ─────────────────────────────────────────────
# Replication Group (cluster mode enabled)
# ─────────────────────────────────────────────
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "HomeFix Redis replication group — cluster mode"

  node_type            = var.node_type
  engine_version       = var.engine_version
  parameter_group_name = aws_elasticache_parameter_group.this.name
  port                 = 6379

  # Cluster mode: distribute keys across 3 shards (num_node_groups).
  # Each shard has 1 primary + num_cache_clusters replicas.
  # Default (num_cache_clusters=3) → 3 shards × 4 nodes = 12 nodes total (over-provisioned).
  # For production use num_cache_clusters=1 (3 shards × 2 nodes = 6 nodes).
  num_node_groups         = 3
  replicas_per_node_group = var.num_cache_clusters

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.security_group_id]

  # Encryption
  at_rest_encryption_enabled  = true
  transit_encryption_enabled  = true
  transit_encryption_mode     = "required"
  auth_token                  = random_password.redis_auth.result
  auth_token_update_strategy  = "ROTATE"
  kms_key_id                  = var.kms_key_arn

  # HA settings
  automatic_failover_enabled = true
  multi_az_enabled           = true

  # Maintenance
  maintenance_window       = "sun:05:00-sun:06:00"
  snapshot_retention_limit = 7
  snapshot_window          = "03:00-04:00"
  auto_minor_version_upgrade = true

  apply_immediately = false

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.redis_slow.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.redis_engine.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "engine-log"
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis"
  })
}

# ─────────────────────────────────────────────
# CloudWatch Log Groups
# ─────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "redis_slow" {
  name              = "/aws/elasticache/${var.name_prefix}/slow-log"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

resource "aws_cloudwatch_log_group" "redis_engine" {
  name              = "/aws/elasticache/${var.name_prefix}/engine-log"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

# ─────────────────────────────────────────────
# CloudWatch Alarms
# ─────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "redis_cpu" {
  alarm_name          = "${var.name_prefix}-redis-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "EngineCPUUtilization"
  namespace           = "AWS/ElastiCache"
  period              = 300
  statistic           = "Average"
  threshold           = 70
  alarm_description   = "Redis engine CPU exceeds 70%"

  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.id
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  alarm_name          = "${var.name_prefix}-redis-memory-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "DatabaseMemoryUsagePercentage"
  namespace           = "AWS/ElastiCache"
  period              = 300
  statistic           = "Average"
  threshold           = 75
  alarm_description   = "Redis memory utilization exceeds 75%"

  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.id
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "redis_evictions" {
  alarm_name          = "${var.name_prefix}-redis-evictions"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "Evictions"
  namespace           = "AWS/ElastiCache"
  period              = 300
  statistic           = "Sum"
  threshold           = 1000
  alarm_description   = "Redis eviction count exceeds 1000 in 5 min window"

  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.id
  }

  tags = var.tags
}
