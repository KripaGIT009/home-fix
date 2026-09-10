output "bucket_documents" {
  description = "Name of the documents bucket"
  value       = aws_s3_bucket.app["documents"].id
}

output "bucket_photos" {
  description = "Name of the photos bucket"
  value       = aws_s3_bucket.app["photos"].id
}

output "bucket_invoices" {
  description = "Name of the invoices bucket"
  value       = aws_s3_bucket.app["invoices"].id
}

output "bucket_media" {
  description = "Name of the media bucket"
  value       = aws_s3_bucket.app["media"].id
}

output "bucket_documents_arn" {
  description = "ARN of the documents bucket"
  value       = aws_s3_bucket.app["documents"].arn
}

output "bucket_photos_arn" {
  description = "ARN of the photos bucket"
  value       = aws_s3_bucket.app["photos"].arn
}

output "bucket_invoices_arn" {
  description = "ARN of the invoices bucket"
  value       = aws_s3_bucket.app["invoices"].arn
}

output "bucket_media_arn" {
  description = "ARN of the media bucket"
  value       = aws_s3_bucket.app["media"].arn
}

output "access_logs_bucket" {
  description = "Name of the central S3 access logs bucket"
  value       = aws_s3_bucket.access_logs.id
}
