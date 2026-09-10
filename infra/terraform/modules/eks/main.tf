##############################################################
# EKS Module — HomeFix
# Multi-AZ EKS cluster with managed node groups and IRSA.
##############################################################

# ─────────────────────────────────────────────
# IAM Role for EKS Cluster
# ─────────────────────────────────────────────
resource "aws_iam_role" "cluster" {
  name = "${var.name_prefix}-eks-cluster-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSClusterPolicy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

resource "aws_iam_role_policy_attachment" "cluster_AmazonEKSVPCResourceController" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

# ─────────────────────────────────────────────
# IAM Role for EKS Node Groups
# ─────────────────────────────────────────────
resource "aws_iam_role" "node" {
  name = "${var.name_prefix}-eks-node-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "node_AmazonEKSWorkerNodePolicy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonEC2ContainerRegistryReadOnly" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_AmazonEKS_CNI_Policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.node.name
}

resource "aws_iam_role_policy_attachment" "node_CloudWatchAgentServerPolicy" {
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
  role       = aws_iam_role.node.name
}

# ─────────────────────────────────────────────
# EKS Cluster
# ─────────────────────────────────────────────
resource "aws_eks_cluster" "this" {
  name     = "${var.name_prefix}-cluster"
  version  = var.cluster_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    security_group_ids      = [var.cluster_sg_id]
    endpoint_private_access = true
    endpoint_public_access  = true  # Set to false for production hardening after initial setup
    public_access_cidrs     = ["0.0.0.0/0"]
  }

  enabled_cluster_log_types = [
    "api",
    "audit",
    "authenticator",
    "controllerManager",
    "scheduler",
  ]

  encryption_config {
    provider {
      key_arn = var.kms_key_arn
    }
    resources = ["secrets"]
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_AmazonEKSClusterPolicy,
    aws_iam_role_policy_attachment.cluster_AmazonEKSVPCResourceController,
  ]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-cluster"
  })
}

# ─────────────────────────────────────────────
# OIDC Provider for IRSA
# ─────────────────────────────────────────────
data "tls_certificate" "cluster" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.cluster.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-eks-oidc"
  })
}

# ─────────────────────────────────────────────
# EKS Managed Node Groups
# ─────────────────────────────────────────────
resource "aws_eks_node_group" "this" {
  for_each = var.node_groups

  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name_prefix}-ng-${each.key}"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = each.value.instance_types

  scaling_config {
    desired_size = each.value.desired_size
    min_size     = each.value.min_size
    max_size     = each.value.max_size
  }

  update_config {
    max_unavailable_percentage = 25
  }

  disk_size = each.value.disk_size_gb

  labels = each.value.labels

  dynamic "taint" {
    for_each = each.value.taints
    content {
      key    = taint.value.key
      value  = taint.value.value
      effect = taint.value.effect
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_AmazonEKSWorkerNodePolicy,
    aws_iam_role_policy_attachment.node_AmazonEC2ContainerRegistryReadOnly,
    aws_iam_role_policy_attachment.node_AmazonEKS_CNI_Policy,
  ]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-ng-${each.key}"
  })

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# ─────────────────────────────────────────────
# EKS Add-ons
# ─────────────────────────────────────────────
resource "aws_eks_addon" "vpc_cni" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "vpc-cni"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                     = var.tags
}

resource "aws_eks_addon" "coredns" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "coredns"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                     = var.tags

  depends_on = [aws_eks_node_group.this]
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "kube-proxy"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                     = var.tags
}

resource "aws_eks_addon" "ebs_csi_driver" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "aws-ebs-csi-driver"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                     = var.tags

  depends_on = [aws_eks_node_group.this]
}
