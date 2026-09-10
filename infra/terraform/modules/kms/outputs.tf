output "key_rds_arn" {
  description = "ARN of the RDS KMS key"
  value       = aws_kms_key.rds.arn
}

output "key_rds_id" {
  description = "ID of the RDS KMS key"
  value       = aws_kms_key.rds.key_id
}

output "key_s3_arn" {
  description = "ARN of the S3 KMS key"
  value       = aws_kms_key.s3.arn
}

output "key_s3_id" {
  description = "ID of the S3 KMS key"
  value       = aws_kms_key.s3.key_id
}

output "key_pii_arn" {
  description = "ARN of the PII field-level KMS key"
  value       = aws_kms_key.pii.arn
}

output "key_pii_id" {
  description = "ID of the PII field-level KMS key"
  value       = aws_kms_key.pii.key_id
}
