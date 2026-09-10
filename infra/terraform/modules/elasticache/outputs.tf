output "replication_group_id" {
  description = "ElastiCache replication group ID"
  value       = aws_elasticache_replication_group.this.id
}

output "configuration_endpoint" {
  description = "Redis cluster-mode configuration endpoint (host:port)"
  value       = "${aws_elasticache_replication_group.this.configuration_endpoint_address}:${aws_elasticache_replication_group.this.port}"
  sensitive   = true
}

output "primary_endpoint" {
  description = "Primary endpoint for non-cluster-mode access"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
  sensitive   = true
}

output "port" {
  description = "Redis port"
  value       = aws_elasticache_replication_group.this.port
}
