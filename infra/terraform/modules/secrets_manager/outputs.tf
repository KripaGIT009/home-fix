output "secret_arns" {
  description = "Map of service name to Secrets Manager secret ARN"
  value       = { for k, v in aws_secretsmanager_secret.service : k => v.arn }
  sensitive   = true
}

output "secret_names" {
  description = "Map of service name to Secrets Manager secret name"
  value       = { for k, v in aws_secretsmanager_secret.service : k => v.name }
}
