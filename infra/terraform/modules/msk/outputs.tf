output "cluster_arn" {
  description = "MSK cluster ARN"
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "MSK cluster name"
  value       = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_tls" {
  description = "TLS bootstrap broker string for Kafka clients"
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
  sensitive   = true
}

output "zookeeper_connect_string" {
  description = "ZooKeeper connection string"
  value       = aws_msk_cluster.this.zookeeper_connect_string
  sensitive   = true
}

output "scram_secret_arn" {
  description = "ARN of the Secrets Manager secret holding SASL/SCRAM credentials"
  value       = aws_secretsmanager_secret.msk_scram.arn
}

output "application_topics" {
  description = "List of application Kafka topic names to be created post-provisioning"
  value       = local.application_topics
}

output "dlq_topics" {
  description = "List of DLQ Kafka topic names to be created post-provisioning"
  value       = local.dlq_topics
}
