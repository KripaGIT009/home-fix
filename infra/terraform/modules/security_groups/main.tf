##############################################################
# Security Groups Module — HomeFix
##############################################################

# ─────────────────────────────────────────────
# ALB / API Gateway Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb-sg"
  description = "Security group for the Application Load Balancer / API Gateway"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP redirect (will be redirected to HTTPS)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb-sg"
  })
}

# ─────────────────────────────────────────────
# EKS Cluster Control Plane Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "eks_cluster" {
  name        = "${var.name_prefix}-eks-cluster-sg"
  description = "Security group for the EKS cluster control plane"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from EKS nodes"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-eks-cluster-sg"
  })
}

# ─────────────────────────────────────────────
# EKS Worker Node Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "eks_node" {
  name        = "${var.name_prefix}-eks-node-sg"
  description = "Security group for EKS worker nodes"
  vpc_id      = var.vpc_id

  ingress {
    description = "All traffic from within the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }

  ingress {
    description = "Node-to-node communication"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-eks-node-sg"
  })
}

# ─────────────────────────────────────────────
# RDS Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds-sg"
  description = "Security group for RDS PostgreSQL"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds-sg"
  })
}

# ─────────────────────────────────────────────
# MSK (Kafka) Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "msk" {
  name        = "${var.name_prefix}-msk-sg"
  description = "Security group for Amazon MSK (Kafka)"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka plaintext from EKS nodes"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  ingress {
    description     = "Kafka TLS from EKS nodes"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  ingress {
    description     = "Kafka SASL/TLS from EKS nodes"
    from_port       = 9096
    to_port         = 9096
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  ingress {
    description     = "ZooKeeper from EKS nodes"
    from_port       = 2181
    to_port         = 2181
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-msk-sg"
  })
}

# ─────────────────────────────────────────────
# ElastiCache Redis Security Group
# ─────────────────────────────────────────────
resource "aws_security_group" "redis" {
  name        = "${var.name_prefix}-redis-sg"
  description = "Security group for ElastiCache Redis"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.eks_node.id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-sg"
  })
}

# ─────────────────────────────────────────────
# Bastion / Management Security Group (optional)
# ─────────────────────────────────────────────
resource "aws_security_group" "management" {
  name        = "${var.name_prefix}-management-sg"
  description = "Security group for management/bastion access"
  vpc_id      = var.vpc_id

  dynamic "ingress" {
    for_each = length(var.allowed_cidr_blocks) > 0 ? [1] : []
    content {
      description = "SSH from allowed CIDRs"
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = var.allowed_cidr_blocks
    }
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-management-sg"
  })
}
