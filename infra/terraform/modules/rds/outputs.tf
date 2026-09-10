output "db_endpoint" {
  description = "RDS writer endpoint hostname"
  value       = aws_db_instance.this.address
}

output "db_port" {
  description = "RDS port"
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Database name"
  value       = aws_db_instance.this.db_name
}

output "db_instance_id" {
  description = "RDS instance identifier"
  value       = aws_db_instance.this.id
}

output "db_instance_arn" {
  description = "RDS instance ARN"
  value       = aws_db_instance.this.arn
}

output "master_secret_arn" {
  description = "ARN of the Secrets Manager secret holding RDS master credentials"
  value       = aws_secretsmanager_secret.rds_master.arn
}
