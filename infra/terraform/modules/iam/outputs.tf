output "irsa_role_arns" {
  description = "Map of '<namespace>/<service-account>' to IAM role ARN for IRSA"
  value       = { for k, v in aws_iam_role.service : k => v.arn }
}

output "irsa_role_names" {
  description = "Map of '<namespace>/<service-account>' to IAM role name"
  value       = { for k, v in aws_iam_role.service : k => v.name }
}
