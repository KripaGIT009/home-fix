output "alb_sg_id" {
  description = "ALB security group ID"
  value       = aws_security_group.alb.id
}

output "eks_cluster_sg_id" {
  description = "EKS cluster control plane security group ID"
  value       = aws_security_group.eks_cluster.id
}

output "eks_node_sg_id" {
  description = "EKS node security group ID"
  value       = aws_security_group.eks_node.id
}

output "rds_sg_id" {
  description = "RDS security group ID"
  value       = aws_security_group.rds.id
}

output "msk_sg_id" {
  description = "MSK security group ID"
  value       = aws_security_group.msk.id
}

output "redis_sg_id" {
  description = "ElastiCache Redis security group ID"
  value       = aws_security_group.redis.id
}

output "management_sg_id" {
  description = "Management security group ID"
  value       = aws_security_group.management.id
}
