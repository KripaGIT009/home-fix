# HomeFix — Deployment runbooks

Step-by-step instructions for running HomeFix locally and for deploying it to AWS. For what runs
where and why, see [ARCHITECTURE.md](ARCHITECTURE.md) section 10; for test users, URLs and the
one-time setup of the local Postgres, Kafka and Redis, see [LOCAL_ACCESS.md](LOCAL_ACCESS.md).

## What gets deployed

The 20 Spring Boot services run as five deployables, and the three web apps as static bundles
behind nginx (locally) or a CDN (production).

| Deployable | Hosts (port) | Container memory (local) |
|------------|--------------|--------------------------|
| `api-gateway` | api-gateway (8080) | 448 MB |
| `identity-app` | auth (8081), customer (8082), provider (8083), verification (8094) | 896 MB |
| `jobs-app` | booking (8084), catalog (8085), pricing (8086), dispatch (8087), location (8092), chat (8093), promotion (8098) | 1280 MB |
| `money-app` | payment (8088), invoice (8089) | 640 MB |
| `engagement-app` | notification (8090), complaint (8091), admin (8095), reporting (8096), rating-review (8097), outbox-processor (8099) | 1152 MB |
| `customer-app`, `provider-app`, `admin-portal` | nginx on 80 (local ports 5173, 5174, 5175) | — |

Each hosted service keeps its own port, configuration (`<service>.yml`), database schema and
Flyway migrations (`db/migration/<service>/`). A variable meant for one hosted service only is
prefixed with its name, e.g. `PRICING_ENGINE__LOGGING_LEVEL_ROOT`; unprefixed variables reach every
service in the deployable.

Platform settings that are the same everywhere, such as the web apps' public URLs, live in the
`config.app_config` table, not in environment files. See [ARCHITECTURE.md](ARCHITECTURE.md),
"Settings in the database".

---

## 1. Local deployment

### Local prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 | Set `JAVA_HOME` for the build; Android builds still need JDK 21 |
| Node.js | 24 | Only to work on a web app outside Docker |
| Docker Desktop | current | WSL2 VM raised to 11 GB (`%UserProfile%\.wslconfig`) |
| PostgreSQL | 18 | Windows service on 5432, database and role `homefix` |
| Kafka | 4.1 (KRaft) | `C:\kafka`, started by hand; container listener on 9094 |
| Redis | 7 or compatible | Memurai, or the `homefix-local-redis` container, on 6379 |

The one-time setup of Postgres, Kafka and Redis is in [LOCAL_ACCESS.md](LOCAL_ACCESS.md) section 1.1.
`docker-compose.infra.yml` runs all three as containers instead, if you prefer.

### Local steps

**1. Install the tools** and check the versions:

```bash
java -version      # 25
node -v            # v24.x
docker info --format '{{.MemTotal}}'   # about 11 GB
```

**2. Create `.env`** with fresh secrets. Never keep the example's `JWT_SECRET`: it signed a token
that leaked in commit `1b5656d` (see [SECURITY.md](SECURITY.md)).

```bash
cp .env.example .env
openssl rand -base64 48     # once per secret: JWT_SECRET, INTERNAL_API_KEY, KMS keys, webhook secrets
```

**3. Prepare the database.** On a new database, create the schemas:

```bash
. docker/local-infra.sh
"$PSQL" -v ON_ERROR_STOP=1 -f docker/init-db.sql
```

To **reset** an existing database, back it up first, then drop every service schema (the
`homefix` role owns them) and recreate them empty. Clear Redis as well, since it holds sessions and
offers that point at the deleted rows. Leave Kafka alone: consumers resume from their committed
offsets, and Kafka on Windows must never delete segments.

```bash
. docker/local-infra.sh
"$(dirname "$PSQL")/pg_dump.exe" -Fc -f "$HOME/homefix-db-backups/homefix-$(date +%Y%m%d-%H%M%S).dump"
schemas=$(psql_q "select string_agg(quote_ident(nspname), ', ') from pg_namespace
                  where nspname not like 'pg_%' and nspname not in ('information_schema','public')")
psql_q "DROP SCHEMA $schemas CASCADE"
"$PSQL" -v ON_ERROR_STOP=1 -f docker/init-db.sql
docker exec homefix-local-redis redis-cli FLUSHALL
```

Restore a backup with `pg_restore -d homefix --clean <file>.dump`.

**4. Build everything** in one reactor build (shared libraries, services, deployables). Maven needs
about 1.5 GB of free memory; close other JVMs, such as IDE language servers, if the machine is short.

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
mvn -q -DskipTests -Djacoco.skip=true package
```

**5. Start Kafka and Redis.** Postgres runs as a Windows service.

```cmd
C:\kafka\bin\windows\kafka-server-start.bat C:\kafka\config\server.properties
docker start homefix-local-redis
```

**6. Start the stack.** `--wait` returns once every container reports healthy, or fails.

```bash
docker compose -f docker-compose.core.yml up -d --build --wait
```

Flyway creates every table on the first start. On a brand-new database the services start before
`config.app_config` exists and use their YAML defaults, which match the seeded rows; from the
second start on they read the table.

**7. Seed the data**, in this order (the provider and agency seeds need the catalog):

```bash
. docker/local-infra.sh
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-catalog.sql           # categories and skill tags
bash docker/seed-pricing.sh                                     # pricing for every subcategory
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-provider-profiles.sql # 2 approved providers in Ara
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-tenants.sql           # demo agency: Ara Home Services
```

Both test providers do plumbing and electrical work, so those bookings are offered to both at once;
cleaning is Provider Two's alone. The test accounts are created by auth-service at startup when
`DEV_SEED_ENABLED=true`.

**8. Prove the flows.**

```bash
bash docker/smoke-flows.sh          # every service's main paths: expect 78/78
bash docker/verify-outbox-flow.sh   # book -> offer -> job -> pay -> wallet, plus agency fallback: 53/53
```

With `PAYMENT_DEFAULT_GATEWAY=razorpay` in `.env`, the three payment checks in the second script
wait for a real Razorpay Checkout and fail. To run them against the simulator without changing
`.env`, restart money-app with an override, then put it back:

```bash
PAYMENT_DEFAULT_GATEWAY=simulator docker compose -f docker-compose.core.yml up -d --wait money-app
bash docker/verify-outbox-flow.sh
docker compose -f docker-compose.core.yml up -d --wait money-app
```

**9. Open the apps:** <http://localhost:5173> (customer), <http://localhost:5174> (provider),
<http://localhost:5175> (admin). OTP codes land in `docker/dev-sms/dev-sms.log` and emails in
`docker/dev-mail/dev-mail.log`.

**10. Change a service.** Rebuild only the deployable that hosts it (`-am` builds what it depends
on), then that container:

```bash
mvn -q -DskipTests -Djacoco.skip=true package -pl apps/jobs-app -am
docker compose -f docker-compose.core.yml up -d --build --wait jobs-app
```

**11. Work on a web app** with hot reload. Vite proxies `/api` to the running stack.

```bash
cd frontend/customer-app
npm install
npm run dev
```

### When something goes wrong

| Symptom | Cause and fix |
|---------|---------------|
| Maven or a container dies with "insufficient memory" | The host is out of RAM or commit. Close other JVMs; keep the WSL VM at 11 GB; build once, not in parallel. |
| OTP sign-in answers `SMS_DELIVERY_FAILED` | The container cannot write `docker/dev-sms/dev-sms.log`. The images run as UID 999; rebuild if an older image is running. |
| `seed-pricing.sh` reports failures | It runs in a `curlimages/curl` container on `homefix-core_default`; the stack must be up and `DEV_SEED_PASSWORD` must match. |
| A booking is never offered to anyone | No approved provider shares a skill tag with the subcategory within range. Check `GET /internal/providers/eligible` and the seed. |
| Health checks pass on the host but calls fail | Docker's port forwarding drops connections under load. Probe from inside the network, e.g. `docker exec homefix-core-api-gateway-1 wget -qO- http://jobs-app:8085/health/readiness`. |
| Kafka stops after the disk fills | Free space on C:, prune the Docker build cache, restart Kafka. |

---

## 2. Production deployment (AWS)

Production runs in `ap-south-1` on EKS, with RDS PostgreSQL, MSK and ElastiCache, provisioned by
Terraform (`infra/terraform`) and deployed by GitHub Actions with the Helm chart `helm/homefix-service`
(one release per deployable).

### Blockers to clear first

Production has never been deployed. These must be fixed before the steps below will work:

| # | Blocker | Fix |
|---|---------|-----|
| 1 | A stray `32wqa` in `infra/terraform/modules/api_gateway/main.tf` breaks `terraform plan` | Remove it (restored in the working tree on 12 October 2026; commit the fix) |
| 2 | The HTTP API has no routes, integrations or VPC Link | ALB ingress, or a VPC Link to an NLB |
| 3 | WAFv2 cannot attach to an HTTP API stage | Attach WAF to the ALB or CloudFront |
| 4 | The API Gateway JWT authorizer needs JWKS; tokens are HMAC-signed | Drop the authorizer, or move auth to RS256 with JWKS |
| 5 | No ECR repositories, GitHub OIDC roles, state bucket or lock table | A bootstrap Terraform stack |
| 6 | Secrets are placeholders and never reach Kubernetes | External Secrets Operator; align key names with Helm |
| 7 | Helm passes no Kafka SASL or Redis TLS settings | Wire SCRAM, TLS and the auth tokens through the values |
| 8 | Flyway migrates from every replica at startup | Run migrations as a pre-deploy Job |
| 9 | No hosting or deploy job for the web apps | S3 + CloudFront and a workflow |
| 10 | NetworkPolicies are not enforced by vpc-cni | Set `enableNetworkPolicy` |
| 11 | IRSA roles (`modules/iam`), namespaces and Secrets Manager entries are keyed by the 19 old service names, but Helm now runs five deployables | One IRSA role, namespace placement and secret per deployable |

### Production prerequisites

- An AWS account and an operator role that can create the stack; Terraform ≥ 1.6, AWS CLI,
  `kubectl`, `helm`, `gh`.
- GitHub environments `staging` and `production` (with required reviewers on `production`) and these
  variables:

| Variable | Where | Purpose |
|----------|-------|---------|
| `IMAGE_PUSH_ENABLED` | repository | `true` to push images to ECR from `main` |
| `STAGING_DEPLOY_ENABLED` | repository | `true` to deploy `main` to staging |
| `AWS_REGION`, `ECR_REGISTRY`, `AWS_CI_ROLE_ARN` | repository | Image push through OIDC |
| `AWS_DEPLOY_ROLE_ARN`, `EKS_CLUSTER_NAME`, `K8S_NAMESPACE` | each environment | Helm deploy through OIDC |
| `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS` | each environment | Data-plane endpoints passed to the releases |
| `MIN_LINE_COVERAGE` | repository | Optional JaCoCo floor |

### Production steps

**1. Clear the blockers** above. At minimum:

```bash
grep -n 32wqa infra/terraform/modules/api_gateway/main.tf    # must print nothing
```

**2. Bootstrap once:** a versioned, KMS-encrypted S3 bucket `homefix-terraform-state`, a DynamoDB
table `homefix-terraform-locks`, ECR repositories named after the five deployables, and the GitHub
OIDC roles for CI and deploy.

**3. Provision the platform (phase 1):**

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars    # fill in real values
terraform init \
  -backend-config="bucket=homefix-terraform-state" \
  -backend-config="key=homefix/production/terraform.tfstate" \
  -backend-config="region=ap-south-1" \
  -backend-config="dynamodb_table=homefix-terraform-locks" \
  -backend-config="encrypt=true"
terraform apply -target=module.kms -target=module.vpc -target=module.security_groups \
  -target=module.eks -target=module.rds -target=module.msk -target=module.elasticache \
  -target=module.s3 -target=module.waf -target=module.api_gateway -target=module.secrets_manager
```

**4. Provision IAM and namespaces (phase 2)** after copying the EKS endpoint and CA into
`terraform.tfvars`:

```bash
terraform apply -target=module.iam -target=module.k8s_namespaces
aws eks update-kubeconfig --region ap-south-1 --name <cluster-name>
```

**5. Load the real secrets** into Secrets Manager (new values per environment; never a dev value)
and sync them to one Kubernetes Secret per deployable, `<deployable>-secrets`, with the keys listed
under `secrets.env` in `helm/services/<deployable>.yaml`.

**6. Prepare the database:** run `docker/init-db.sql` against RDS to create the schemas, then
migrate before the first rollout (today Flyway runs at startup; see blocker 8).

**7. Install the cluster add-ons:** the AWS Load Balancer Controller, External Secrets Operator,
and monitoring with the values in `infra/k8s/observability/` (Prometheus, Grafana dashboards,
Fluent Bit, alert rules, HPA and KEDA objects).

**8. Merge to `main`.** `ci.yml` calls `build-services.yml`: one Java 25 reactor build with the
tests, an image per deployable, a Trivy scan that fails on fixable CRITICAL findings, and a push to
ECR through OIDC. `frontend-ci.yml` checks and builds the three web apps on Node 24.

**9. Staging deploys itself** through `deploy-services.yml`: preflight checks, then for each
deployable

```bash
helm upgrade --install homefix-<deployable> helm/homefix-service \
  --namespace "$K8S_NAMESPACE" \
  -f helm/homefix-service/values-staging.yaml -f helm/services/<deployable>.yaml \
  --set-string image.repository="$ECR_REGISTRY/<deployable>" --set-string image.tag=<sha> \
  --atomic --wait --timeout 15m --history-max 10
```

and a health check through its Service; a failed check rolls that release back.

**10. Set the app URLs** once per environment, then restart the deployables. Until this is done,
staff invitation links point at `localhost`.

```sql
UPDATE config.app_config SET config_value = 'https://app.homefix.example',   updated_at = now()
 WHERE config_key = 'homefix.apps.customer-app-url';
UPDATE config.app_config SET config_value = 'https://pro.homefix.example',   updated_at = now()
 WHERE config_key = 'homefix.apps.provider-app-url';
UPDATE config.app_config SET config_value = 'https://admin.homefix.example', updated_at = now()
 WHERE config_key = 'homefix.apps.admin-portal-url';
```

**11. Promote to production** with the same commit that passed staging. The run checks the SHA had
a green CI run on `main`, waits for the `production` reviewers, deploys up to five releases in
parallel and tags a `production-N` release.

```bash
gh workflow run cd-production.yml -f image_tag=<full-40-char-sha> -f services=all
```

**12. Publish the web apps** (manual today, blocker 9) with absolute API URLs:

```bash
cd frontend/customer-app
VITE_API_BASE_URL=https://api.homefix.example npm ci && npm run build
aws s3 sync dist s3://<customer-app-bucket> --delete
```

**13. Smoke production:** sign in with a test account, list the catalog, place and cancel a test
booking, and check `curl https://<api>/config/client` returns the production URLs.

**14. Watch, then roll back if needed.** Watch the CloudWatch and Prometheus alarms, the `.DLT`
topics and error rates. To roll back, re-run `cd-production.yml` with the previous SHA. Migrations
only move forward, so a release that changes the schema must stay compatible with the release
before it.

### Release checklist

- [ ] CI green on the SHA, images in ECR for all five deployables
- [ ] Staging healthy, smoke checks passed on staging
- [ ] `config.app_config` URLs set for the environment
- [ ] No secret in any environment equals a value from `.env.example` or the repository history
- [ ] Production reviewers approved; `production-N` tag created
- [ ] Alarms quiet for an hour after the rollout
