terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.27"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.13"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "s3" {
    # Configure via -backend-config or terraform.tfvars
    # bucket         = "homefix-terraform-state"
    # key            = "homefix/terraform.tfstate"
    # region         = "ap-south-1"
    # dynamodb_table = "homefix-terraform-locks"
    # encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "HomeFix"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Repository  = "homefix-platform"
    }
  }
}

provider "kubernetes" {
  host                   = var.eks_cluster_endpoint
  cluster_ca_certificate = base64decode(var.eks_cluster_ca_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args = [
      "eks",
      "get-token",
      "--cluster-name",
      var.eks_cluster_name,
      "--region",
      var.aws_region,
    ]
  }
}

provider "helm" {
  kubernetes {
    host                   = var.eks_cluster_endpoint
    cluster_ca_certificate = base64decode(var.eks_cluster_ca_data)

    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args = [
        "eks",
        "get-token",
        "--cluster-name",
        var.eks_cluster_name,
        "--region",
        var.aws_region,
      ]
    }
  }
}
