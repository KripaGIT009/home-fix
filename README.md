# HomeFix

HomeFix is an on-demand home-services marketplace. Customers book verified providers for
scheduled or emergency jobs (plumbing, electrical, AC, cleaning). Providers are onboarded
through document verification and a background check, then matched to jobs by a dispatch
engine. Service agencies (tenants) can run their own teams of providers on the platform.

## Repository layout

| Path | Contents |
|------|----------|
| [`pom.xml`](pom.xml) | Maven reactor and parent of every backend module: Java 25, Spring Boot 4.1 |
| [`services/`](services/) | 20 Spring Boot services: the API gateway and 19 domain services (plain jars) |
| [`apps/`](apps/) | The four deployables that host the domain services, several per JVM: `identity-app`, `jobs-app`, `money-app`, `engagement-app` |
| [`shared/`](shared/) | Shared Maven libraries: observability, security, resilience, transactional outbox, `config.app_config` settings, and the service host the deployables use |
| [`frontend/`](frontend/) | Three React 19 + TypeScript 6 + Vite 8 apps (Node 24): `customer-app`, `provider-app`, `admin-portal` |
| [`docker/`](docker/) | Database init and seed scripts, smoke tests, local-infra helpers |
| [`helm/`](helm/) | Helm chart (`homefix-service`) and values for each of the five deployables |
| [`infra/terraform/`](infra/terraform/) | AWS infrastructure: VPC, EKS, RDS, MSK, ElastiCache, KMS, IAM |
| [`infra/k8s/`](infra/k8s/) | Cluster-level manifests (observability) |
| [`docs/`](docs/) | Architecture, API contracts and local-run guide |

## Services and deployables

The 20 services run as five deployables (five JVMs, five images) instead of twenty. A
deployable under `apps/` starts each of its services as its own Spring application context
inside one JVM (`shared/homefix-service-host`), each on its own port with its own
configuration (`<service>.yml`), database schema and beans, so a service can still be moved
to its own deployable without code changes.

| Deployable | Services (port) |
|------------|-----------------|
| api-gateway | api-gateway (8080) |
| identity-app | auth-service (8081), customer-service (8082), provider-service (8083), verification-service (8094) |
| jobs-app | booking-service (8084), catalog-service (8085), pricing-engine (8086), dispatch-engine (8087), location-service (8092), chat-service (8093), promotion-service (8098) |
| money-app | payment-service (8088), invoice-service (8089) |
| engagement-app | notification-service (8090), complaint-service (8091), admin-service (8095), reporting-service (8096), rating-review-service (8097), outbox-processor (8099) |

An environment variable meant for one hosted service only is prefixed with its name, e.g.
`PRICING_ENGINE__LOGGING_LEVEL_ROOT`; unprefixed variables reach every service in the
deployable. Platform settings shared by all of them (such as the web apps' public URLs) are
rows of the `config.app_config` table; see [ARCHITECTURE.md](docs/ARCHITECTURE.md#settings-in-the-database-configapp_config).

The services share one PostgreSQL database with a schema per service (migrated by Flyway at
startup), communicate asynchronously over Kafka through a transactional outbox, and use Redis
for caching and rate limiting. All client traffic goes through the gateway.

The web apps run on `5173` (customer), `5174` (provider) and `5175` (admin). The customer and
provider apps also ship as Android and iOS apps via Capacitor; see
[`frontend/MOBILE.md`](frontend/MOBILE.md).

## Running locally

Locally the services and web apps run in Docker Compose, while PostgreSQL, Kafka and Redis run
on the host machine. Full setup, including the one-time infrastructure configuration, seeding
and test users, is in [`docs/LOCAL_ACCESS.md`](docs/LOCAL_ACCESS.md). In short:

```bash
cp .env.example .env            # first time only

# Build the deployables (JDK 25; one reactor build from the repository root)
mvn -q -DskipTests -Djacoco.skip=true package

# Start Kafka on the host first, then the stack
docker compose -f docker-compose.core.yml up -d --build --wait
```

The images copy the prebuilt jar from `apps/<deployable>/target/` (and
`services/api-gateway/target/`), so rebuild before `--build` after changing a service.

To work on a web app on its own:

```bash
cd frontend/customer-app
npm install
npm run dev
```

## Deployment

Production targets AWS: Terraform provisions the platform (EKS, RDS PostgreSQL, MSK, ElastiCache)
and each of the five deployables is deployed to EKS with the Helm chart in [`helm/`](helm/), configured through
per-environment values and Secrets. See [`infra/terraform/README.md`](infra/terraform/README.md).

## Documentation

- [Architecture and diagrams](docs/ARCHITECTURE.md)
- [API contracts](docs/API_CONTRACTS.md)
- [Running locally, test users and URLs](docs/LOCAL_ACCESS.md)
- [Mobile builds](frontend/MOBILE.md)
- [Codebase review findings](CODEBASE_REVIEW.md)

## Signing in locally

All three apps accept email and password; the customer and provider apps also take mobile OTP
and Google. The seeded test accounts sign in as `<username>@homefix.local` (for example
`customer@homefix.local`, `provider@homefix.local`, `admin@homefix.local`) with the password
from `DEV_SEED_PASSWORD` in `.env`. New customers and providers can sign up with an emailed
code, staff join by invitation, and agencies register themselves for platform-admin approval.
Locally, emailed codes and invitation links are written to `docker/dev-mail/dev-mail.log` and
OTP codes to `docker/dev-sms/dev-sms.log`. See
[`docs/LOCAL_ACCESS.md`](docs/LOCAL_ACCESS.md).

## What is real and what is stubbed

Google and Apple sign-in and Razorpay payments (test mode locally, with the keys in `.env`) are
real integrations. SMS, email, document storage and background checks are not: there is no email
provider yet, and an admin records each provider's background check in the Admin Portal's
Verification Queue. The architecture document marks each one.
