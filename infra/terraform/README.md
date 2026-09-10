# HomeFix — AWS Infrastructure (Terraform)

Production-grade AWS infrastructure for the HomeFix on-demand home services platform.

## Architecture

| Module | Resources |
|---|---|
| `vpc` | VPC, public/private/database subnets (3 AZs), NAT GWs, Flow Logs |
| `security_groups` | SGs for ALB, EKS cluster/nodes, RDS, MSK, Redis, management |
| `kms` | 3 CMKs: RDS, S3/MSK/Redis, PII field-level |
| `eks` | EKS 1.29 cluster, managed node groups, OIDC provider for IRSA, core add-ons |
| `iam` | IRSA roles (one per microservice), least-privilege S3/KMS/Secrets policies |
| `rds` | PostgreSQL 15 Multi-AZ, enhanced monitoring, Performance Insights, deletion protection |
| `msk` | MSK Kafka 3.6, SASL/SCRAM, TLS, CloudWatch logging, per-topic configs |
| `elasticache` | Redis 7.1 replication group — cluster mode (3 shards × 2 nodes) |
| `s3` | 4 buckets (documents, photos, invoices, media) + access-log bucket, SSE-KMS |
| `waf` | WAFv2 Regional — OWASP CRS, Known Bad Inputs, SQLi, IP reputation, rate limiting |
| `api_gateway` | HTTP API v2, JWT authorizer, throttling, WAF association, access logging |
| `secrets_manager` | Placeholder secrets for all 19 microservices (rotated by CI/CD) |
| `k8s_namespaces` | 6 namespaces with ResourceQuotas, LimitRanges, and NetworkPolicies |

## Prerequisites

- AWS CLI configured with credentials for the target account
- Terraform ≥ 1.6.0
- `kubectl` and `aws` CLI in PATH (for EKS kubeconfig)
- S3 bucket and DynamoDB table for remote state (see `versions.tf`)

## Deployment

### Step 1 — Configure backend and variables

```bash
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your values
```

Configure the S3 backend in `versions.tf` or via CLI:

```bash
terraform init \
  -backend-config="bucket=homefix-terraform-state" \
  -backend-config="key=homefix/production/terraform.tfstate" \
  -backend-config="region=ap-south-1" \
  -backend-config="dynamodb_table=homefix-terraform-locks" \
  -backend-config="encrypt=true"
```

### Step 2 — Phase 1: Core AWS infrastructure

Apply everything except the Kubernetes resources (which need the EKS cluster to exist first):

```bash
terraform apply \
  -target=module.kms \
  -target=module.vpc \
  -target=module.security_groups \
  -target=module.eks \
  -target=module.rds \
  -target=module.msk \
  -target=module.elasticache \
  -target=module.s3 \
  -target=module.waf \
  -target=module.api_gateway \
  -target=module.secrets_manager
```

### Step 3 — Capture EKS outputs for provider bootstrap

```bash
EKS_ENDPOINT=$(terraform output -raw eks_cluster_endpoint)
EKS_NAME=$(terraform output -raw eks_cluster_name)
EKS_CA=$(terraform output -raw eks_cluster_certificate_authority_data)

# Append to terraform.tfvars
echo "eks_cluster_endpoint = \"$EKS_ENDPOINT\"" >> terraform.tfvars
echo "eks_cluster_name     = \"$EKS_NAME\""     >> terraform.tfvars
echo "eks_cluster_ca_data  = \"$EKS_CA\""       >> terraform.tfvars
```

### Step 4 — Phase 2: IAM IRSA roles and Kubernetes namespaces

```bash
terraform apply -target=module.iam -target=module.k8s_namespaces
```

### Step 5 — Verify

```bash
# Update kubeconfig
aws eks update-kubeconfig --region ap-south-1 --name homefix-production-cluster

# Confirm nodes are Ready
kubectl get nodes

# Confirm namespaces
kubectl get namespaces

# Confirm network policies
kubectl get networkpolicy --all-namespaces
```

## Module Dependency Graph

```
kms
├── vpc
│   └── security_groups
│       ├── eks ── oidc → iam
│       ├── rds
│       ├── msk
│       └── elasticache
├── s3 → iam
├── waf → api_gateway
└── secrets_manager (depends on rds outputs)

eks → k8s_namespaces
```

## Secret Rotation

Secrets Manager secrets are created with placeholder values. Rotate them before deploying microservices:

```bash
aws secretsmanager put-secret-value \
  --secret-id homefix-production/auth-service \
  --secret-string '{"db_password":"real-password","jwt_secret":"real-secret",...}'
```

## Security Notes

- EKS public endpoint is enabled for initial setup. Restrict `public_access_cidrs` or disable `endpoint_public_access` after bootstrap.
- RDS has `deletion_protection = true` and `prevent_destroy` lifecycle — intentional.
- All S3 buckets deny non-TLS and non-KMS PUT requests via bucket policies.
- WAF rate-limits per IP at 2,000 requests/5 min. Tune `rate_limit_per_5min` as needed.
