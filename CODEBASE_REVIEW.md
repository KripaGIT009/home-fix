# HomeFix Platform — Codebase Review

**Date:** 2026-09-10
**Repository:** `KripaGIT009/home-fix` (branch `main`)
**Scope:** entire repository — 20 Spring Boot services, 4 shared libraries, 3 React apps, Docker Compose, Helm, Kubernetes manifests, Terraform, GitHub Actions, and the `.kiro` specifications.

---

## 1. Executive summary

HomeFix is an on-demand home-services marketplace (customers book verified providers for scheduled or emergency jobs). The codebase is a well-structured, spec-driven implementation of a 20-service event-driven architecture. The engineering fundamentals are strong: consistent hexagonal layout, transactional outbox + idempotent consumers, `BigDecimal` money handling, state machines as single sources of truth, injected clocks, property-based tests, and hardened Terraform.

However, the platform is **not deployable or safe to expose as it stands**. The review found a small number of systemic gaps that affect nearly every service, plus several broken inter-service contracts that prevent the core booking flow from completing end to end.

**Overall verdict:** solid foundation, pre-alpha integration state. The remaining work is mostly wiring, authorization, and operational plumbing rather than domain logic.

### The seven issues that matter most

| # | Issue | Blast radius |
|---|-------|--------------|
| 1 | **No role-based authorization is configured anywhere except admin- and reporting-service.** The shared RBAC filter passes through when no rule matches, and the gateway only checks that a token is valid. Any authenticated customer can approve refunds, verify providers, moderate reviews, change pricing/dispatch parameters, and mutate the catalog. | All 18 other services |
| 2 | **No object-level ownership checks.** Controllers never compare the JWT subject to the `{id}` in the path. Any user can act on any booking, provider profile, wallet, invoice, review or verification. | booking, provider, customer, verification, rating, location, payment, invoice, complaint |
| 3 | **Self-service ADMIN registration.** The auth registration endpoint accepts any role name from the request body, including `ADMIN`. | Whole platform |
| 4 | **Default secrets in every `application.yml`.** JWT signing secret, DB password, webhook secrets and AES data keys all have hard-coded fallbacks; a missing env var silently ships forgeable tokens and decryptable PII. | All services |
| 5 | **The booking → dispatch → accept flow cannot complete.** Dispatch calls internal booking endpoints that do not exist, the `BookingCreated` event schema differs between producer and consumer (search runs at lat/lon 0,0), the requested state transition is illegal, and the outbox relay polls the wrong DB schema so events are never published at all. | Core product flow |
| 6 | **No database migrations.** All services use `ddl-auto: validate`; local Compose overrides to `update`; there is no Flyway/Liquibase and `init-db.sql` creates only schemas. Any non-Compose deployment fails at startup. | All DB-backed services |
| 7 | **CI/CD deploys a non-existent `hello-world-service`**, never builds the jars its Dockerfiles copy, never installs the `shared/` modules, and the Helm chart probes `/actuator/health/*` on port 8080 while services serve `/health/*` on 8081–8099. Nothing real is tested, built or deployed by the pipeline. | Delivery |

A prioritised remediation plan is in [Section 11](#11-remediation-roadmap).

### Housekeeping done during this review

- The repository was pushed to GitHub as commit `1b5656d` shortly before the review began. That commit tracked **`tok.tmp` (a signed JWT)**, every `target/` and `dist/` directory, and `*.tsbuildinfo` files.
- A follow-up commit `59dce89` added a `.gitignore` and removed those files from tracking. **The JWT remains in the history of commit `1b5656d` on GitHub.** Treat it as leaked: rotate the signing secret it was minted with (it is likely the local-dev secret), and consider rewriting history if the repo is or becomes public.

---

## 2. Repository overview

### Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21, Spring Boot 3.2.5, Spring Security, Spring Data JPA, Spring Kafka 3.1.4, Spring Cloud Gateway 2023.0.1 (WebFlux), jjwt 0.12.6, Resilience4j 2.2.0, OpenTelemetry API 1.37, Micrometer/Prometheus, OpenPDF |
| Data | PostgreSQL 16 (one schema per service in one database), Redis 7, Apache Kafka 3.7 (KRaft) |
| Frontend | React 18.3, TypeScript (strict), Vite 5, react-router 6, TanStack Query 5, Zustand 5, MUI 6, react-hook-form + zod, axios |
| Testing | JUnit 5, Mockito, jqwik (property tests), Pact (contract tests), Embedded Kafka, H2, JaCoCo |
| Infra | Docker Compose (local), Helm (one shared chart), Kubernetes manifests (HPA, KEDA, Prometheus, Fluent Bit), Terraform for AWS (VPC, EKS 1.29, RDS, MSK, ElastiCache, S3, WAF, API Gateway v2, Secrets Manager), GitHub Actions |

### Layout

```
home-fix/
├── .github/workflows/      ci.yml, pr-checks.yml, cd-production.yml, frontend-ci.yml
├── .kiro/specs/            requirements.md, design.md, tasks.md (the product & architecture spec)
├── docker/                 init-db.sql, seed scripts, smoke tests, scratch output files
├── docker-compose.core.yml Full local stack (Postgres, Redis, Kafka, 20 services, 3 SPAs)
├── frontend/               admin-portal, customer-app, provider-app (independent Vite projects)
├── helm/homefix-service/   Single generic chart used for every service
├── infra/k8s/              Observability + autoscaling manifests
├── infra/terraform/        AWS root module + 12 sub-modules
├── services/               20 Spring Boot services (each: pom.xml, Dockerfile, src/)
└── shared/                 4 Spring Boot auto-configured libraries
```

### Size

| Area | Count |
|------|-------|
| Backend services | 20 (≈ 62,000 lines of Java in `src/main`) |
| Shared libraries | 4 (≈ 3,900 lines) |
| Backend test files | 244 |
| Frontend source files | 214 (`.ts`/`.tsx`) across three apps |
| Frontend test files | 0 |
| Spec tasks marked complete | 46 of 46 |

There is no root aggregator `pom.xml`; each of the 24 Maven modules is a standalone build that depends on `com.homefix.shared:*:1.0.0-SNAPSHOT`, so the shared modules must be `mvn install`ed first.

---

## 3. Intended architecture (from `.kiro/specs`)

The spec describes an AWS-native platform: React SPAs → API Gateway → 19 Spring Boot services on EKS, each owning its own Postgres schema, communicating asynchronously through Kafka using the transactional-outbox pattern with a dedicated Outbox Processor and idempotent consumers (dead-letter after three retries). Authentication is OTP-based with short-lived HS256 JWTs and rotating refresh tokens in Redis; the gateway validates signatures and each service enforces RBAC through shared middleware. Resilience4j wraps every synchronous call. Secrets live in AWS Secrets Manager and must never appear in source, images or ConfigMaps. Observability is OpenTelemetry + Prometheus + Grafana with `/health/liveness` and `/health/readiness` on every service.

`tasks.md` marks all 46 implementation tasks as complete. As the findings below show, several of those tasks (notably CI/CD, deployment, RBAC enforcement and the inter-service integration tasks) are not actually finished; the checklist should be reopened for them.

### Actual runtime topology

```
Browser SPA ──/api──▶ nginx ──▶ api-gateway :8080 ──▶ auth-service :8081 (introspect per request)
                                  │  routes /auth, /customers, /bookings, /admin/** ... (17 services)
                                  ▼
                      domain services :8081–:8099 ──JPA──▶ Postgres (schema per service)
                                  │                          ▲
                                  │ outbox_event rows        │ polls public.outbox_event (wrong schema!)
                                  ▼                          │
                             Kafka topics ◀────────── outbox-processor :8099
                                  │
                                  ▼
       idempotent consumers: dispatch, notification, invoice, chat, rating, location
```

---

## 4. Backend services

Ports are the `application.yml` defaults; Compose overrides several to avoid collisions.

| Service | Port | Schema | Purpose | Key endpoints | Events |
|---------|------|--------|---------|---------------|--------|
| api-gateway | 8080 | — (Redis) | Spring Cloud Gateway; HTTPS redirect, correlation ID, WAF regex filter, per-request JWT introspection, OTP + per-user rate limiting | routes for 17 services | — |
| auth-service | 8081 | auth | OTP registration/login, 15-min HS256 access tokens, 30-day rotating refresh tokens (family replay detection), Google/Apple OIDC | `/auth/register/otp`, `/auth/register/verify`, `/auth/token/refresh`, `/auth/login/social`, `/auth/logout`, `/auth/introspect` | — |
| customer-service | 8082 | customer | Customer profile (PII AES-GCM encrypted), addresses, GDPR deletion sweep | `/customers/{id}/profile`, `/addresses`, `/deletion`, `/location/detect` | — |
| provider-service | 8083 | provider | Provider profile, skills, availability, radius, wallet, settlements, encrypted bank ref | `/providers/{id}/...` | — |
| verification-service | 8084 | verification | KYC document upload, background check, state machine + audit chain, admin approve/reject/suspend | `/verifications/{providerId}/...`, `/admin/verifications/...` | — |
| booking-service | 8085 | booking | Booking aggregate, 18-state lifecycle, saga log, job execution milestones, parts, media | `/bookings`, `/bookings/{ref}/{confirmation,cancellation,on-the-way,arrived,start,pause,resume,parts,quote/*,complete}` | pub: BookingCreated, ProviderArriving, ProviderArrived, JobStarted, JobCompleted |
| catalog-service | 8085 | catalog | Service categories/subcategories, admin CRUD, cached customer listing | `/catalog/categories`, `/admin/catalog/**` | — |
| pricing-engine | 8086 | — | Itemised estimates (surge, night/weekend, distance, coupons), provider overrides, admin parameters | `/pricing/estimate`, `/pricing/overrides`, `/admin/pricing/parameters` | — |
| dispatch-engine | 8087 | dispatch | Ranks providers with configurable weights, sequential offers under Redis lock, radius expansion, emergency/scheduled bulkheads | `/admin/dispatch/weights` | sub: BookingCreated · pub: ProviderAccepted |
| location-service | 8087 | location | Provider GPS ingest (1 per 5 s), Redis last-location, SSE tracking stream, haversine ETA | `/locations/{bookingId}` (+ `/stream`) | sub: JobStarted |
| payment-service | 8088 | payment | Multi-gateway (Razorpay/Stripe stubs) payments, HMAC callbacks, refunds, settlements, idempotency (DB unique + Redis NX) | `/payments`, `/payments/callbacks/{id}`, `/payments/{id}/refunds`, `/payments/settlements` | pub: PaymentCompleted |
| invoice-service | 8089 | invoice | Consumes PaymentCompleted, race-safe `INV-YYYY-MM-NNNNNN` numbering, OpenPDF rendering, monthly provider statements | `/invoices/customers/{id}`, `/invoices/providers/{id}/statements/{y}/{m}` | sub: PaymentCompleted |
| promotion-service | 8089 | promotion | Coupons with usage limits; SERIALIZABLE counter transactions with retry | `/coupons`, `/coupons/{validate,redeem,cancel}` | — |
| notification-service | 8090 | notification | Consumes 11 lifecycle topics, template/channel resolution, delivery log dedupe, retries (SMS/email/push adapters are logging stubs) | actuator only | sub: 11 topics |
| rating-review-service | 8090 | rating | Two-way reviews, 7-day prompts, weighted aggregate, fraud detection (IP burst, SD deviation, fresh account), moderation | `/reviews`, `/reviews/{id}/{approval,removal}` | sub: PaymentCompleted · pub: ReviewSubmitted |
| complaint-service | 8091 | complaint | Complaints with SLA deadlines and scheduled escalation, refunds, settlement holds | `/complaints`, `/complaints/{id}/{status,refund,dispute}`, `/complaints/stats` | pub: ComplaintCreated, ComplaintStatusChanged |
| chat-service | 8093 | chat | Per-booking channels, STOMP over `/ws/chat`, phone masking, retention timestamps | `/chat/channels/{bookingId}/messages` | sub: ProviderAccepted, PaymentCompleted, BookingCancelled |
| admin-service | 8095 | admin | Dashboard, dispatch weights, system config (SUPER_ADMIN), append-only audit log, verification queue | `/admin/**` | — |
| reporting-service | 8096 | — | Sync/async reports, CSV and hand-rolled PDF export; only service with real RBAC rules + FINANCE_ADMIN gating | `/reports`, `/reports/export` | — |
| outbox-processor | 8099 | (public) | Polls `outbox_event` every 1 s, publishes to Kafka with exponential backoff, marks FAILED after 10 attempts | actuator only | pub: all |

Default-port collisions: booking/catalog (8085), dispatch/location (8087), invoice/promotion (8089), notification/rating (8090). Compose remaps them, but the defaults should be unique.

---

## 5. Shared libraries

| Module | Provides | Notes |
|--------|----------|-------|
| homefix-shared-security | `CorrelationIdFilter`, `JwtValidationFilter` (HS256, roles claim → `ROLE_*`), `RbacEnforcementFilter` (Ant patterns from `homefix.security.rbac.endpoint-roles`), auto-configured | Filter passes through when no rule matches; matches on raw `getRequestURI()` so `;param` segments bypass it. 28 unit tests. |
| homefix-shared-outbox | `OutboxEventEntity`/repository, `OutboxEventPublisher` (`Propagation.MANDATORY`), `KafkaProducerTemplate` (fails fast without `acks=all` + idempotence), `IdempotentKafkaConsumer` (processed_event table, 3 retries with 5 s sleeps, DLQ), `DlqForwarder` | Atomicity proven with an H2 test. Handler and dedupe marker run in separate transactions. |
| homefix-shared-resilience | `ResilienceFactory`, `ResilientCall` (timeout → retry → breaker → fallback), `TimeoutProfile` | Declared in all 20 service POMs; **used by only a handful** (booking, dispatch). Swallows non-transient exceptions into the fallback. |
| homefix-shared-observability | Logstash JSON logging base, `PiiMaskingConverter`/`PiiScrubber`, `TracingSupport`, `ErrorResponseDto` | Stack traces are not scrubbed despite the config comment; card regex masks any 13–19 digit number. |

The shared libs are the strongest part of the codebase. The most impactful bug is the RBAC filter's fail-open default combined with no service configuring rules.

---

## 6. Frontends

Three independent Vite projects that are effectively copy-paste forks of each other (about 40 byte-identical files: API client, token bridge, auth hooks/schemas, query client, error boundary, ESLint/TS/Docker/nginx config).

| App | Audience | Routes | Notes |
|-----|----------|--------|-------|
| customer-app | Customers, mobile-first | splash, login, home, category, book/estimate/professionals, track, chat, history, profile, help | SSE for live location, WebSocket for chat, Google Sign-In loaded lazily |
| provider-app | Providers, mobile-first | splash, login, dashboard, verification, job request/active/complete, earnings, profile | Two incompatible booking-status enums within the app |
| admin-portal | Staff, desktop | login, dashboard + 15 modules (users, providers, verification, categories, pricing, dispatch, bookings, payments, complaints, reviews, coupons, notifications, reports, audit logs, system config) | SUPER_ADMIN gate on system config only |

All three: axios instance with `Authorization: Bearer` and `X-Correlation-ID` interceptors, access token in memory, refresh token + profile persisted to `localStorage`, nginx SPA fallback with immutable asset caching, dev proxy `/api/auth → :8081`, `/api → :8080`.

Strengths: strict TypeScript, `eslint --max-warnings 0` with type-checked and jsx-a11y rules, zero `any`, normalised `ApiError`, client-side guards mirroring backend rules, sensible a11y basics.

Weaknesses: **zero tests**, no shared package, sessions do not survive a page reload (see findings), and the admin login calls endpoints that do not exist.

---

## 7. Infrastructure and delivery

**Docker Compose** (`docker-compose.core.yml`): Postgres 16 (host 5533), Redis 7 (6380), Kafka 3.7.1 KRaft (9095), all 20 services on 8080–8099 with `wget` readiness health checks and `depends_on: service_healthy`, three SPAs on 5173–5175. Images are runtime-only; jars must be built on the host first. All secrets are inline literals (`homefix`, a shared dev JWT secret repeated ~19 times). Hibernate is forced to `ddl-auto: update`. OTPs are written to a mounted file and echoed to logs. Two services run Spring Security at DEBUG.

**Helm** (`helm/homefix-service`): one generic chart with Deployment, Service, ConfigMap, HPA v2, PDB, ServiceAccount, topology spread, and a good pod security context (non-root, read-only FS, drop ALL, seccomp). But it probes `/actuator/health/*` and scrapes `/actuator/prometheus` on port 8080; every service actually serves `/health/*` and `/prometheus` on its own port. No Ingress, NetworkPolicy or Secret template.

**Kubernetes manifests** (`infra/k8s`): HPAs, KEDA ScaledObjects for Kafka consumers, Prometheus/Alertmanager with SLO alerts, Fluent Bit → CloudWatch, Grafana dashboards. They target bare Deployment names in six domain namespaces; the Helm chart and CI produce `homefix-<svc>` in `staging`/`production`. Committed `Secret` objects contain `PLACEHOLDER` credentials; Grafana admin password is in a tracked values file. Nothing applies these manifests.

**Terraform** (`infra/terraform`, AWS ap-south-1): well-hardened and reasonably complete (VPC with flow logs, EKS with IRSA and KMS secret encryption, Multi-AZ encrypted RDS with deletion protection, MSK TLS+SCRAM, encrypted ElastiCache, KMS-encrypted S3 with TLS-only policies, WAF, HTTP API Gateway with JWT authorizer, per-service IRSA roles and Secrets Manager shells, namespace quotas). Gaps: EKS API endpoint public to `0.0.0.0/0`, S3 backend commented out, fragile two-phase `-target` apply, API Gateway JWT issuer/audience cannot match auth-service tokens, no NetworkPolicies or analytics store.

**GitHub Actions:**

| Workflow | Trigger | What it does | Status |
|----------|---------|--------------|--------|
| ci.yml | push main/release, dispatch | lint → unit tests → Pact gate → Docker build + Trivy → Helm deploy to staging → integration tests | Broken: defaults to `hello-world-service`; never runs `mvn package`; never installs `shared/`; Trivy `exit-code: 0` and `@master`; static AWS keys; coverage step `\|\| true` |
| pr-checks.yml | pull_request | change detection, Checkstyle matrix, `mvn verify` with 80 % JaCoCo gate, Helm lint, actionlint, Dockerfile dry-build | Partly works; Checkstyle plugin is not in any pom; Dockerfile check swallows failures and references a missing `Dockerfile.base` |
| cd-production.yml | dispatch or after CI | verify ECR image, Helm deploy with `--atomic`, smoke test, re-tag, GitHub Release, rollback job | Auto-promotion deploys a tag CI never built; smoke test hits `/actuator/health` and passes via `\|\| echo` |
| frontend-ci.yml | push/PR | prettier, eslint, typecheck, build for all three apps | Works; no tests to run |

---

## 8. Findings

Each finding was verified by reading the code. Line numbers refer to the current `main`.

### 8.1 Cross-cutting (affect most services)

| Sev | Finding | Where | Fix |
|-----|---------|-------|-----|
| **Critical** | RBAC never configured; shared filter fails open; gateway authorizes nothing by route. Customers can call agent/admin endpoints in complaint, rating, verification, pricing, dispatch, catalog, payment, promotion, provider. | `shared/.../RbacEnforcementFilter.java:68-72`; only `AdminRbacConfig` and `ReportingRbacConfig` register rules | Register `endpoint-roles` per service (or `hasRole` matchers in each `WebSecurityConfig`), make the filter deny-by-default for `/admin/**`, add a security-chain test per service. |
| **Critical** | No object-level authorization: path `{id}` never compared to JWT subject. | booking `BookingController.java:101-119`, `JobExecutionController.java:57-135`; provider `ProviderController.java:51`; customer; invoice `InvoiceController.java:39-51`; verification `VerificationController.java:49-64`; location `LocationController.java:48-79`; payment `PaymentController.java:76-88` | Introduce a shared `currentUser()` + ownership assertion helper; enforce in every service; add tests. |
| **High** | Default secrets in config: JWT secret, DB password, Razorpay/Stripe webhook secrets, AES data keys (customer-service key decodes to 32 zero bytes). | every `services/*/src/main/resources/application.yml` (e.g. `auth-service:28`, `payment-service:28,47,50,57`, `customer-service:38`) | Remove defaults so startup fails when unset; load from Secrets Manager/ExternalSecrets. |
| **High** | No schema migrations. `ddl-auto: validate` everywhere, Compose forces `update`, `init-db.sql` creates schemas only, outbox/processed_event tables likewise unprovisioned. | all DB-backed services; `docker/init-db.sql:5-20`; `docker-compose.core.yml` | Add Flyway per service with baseline migrations; keep `validate`. |
| **High** | Shared resilience library declared in all 20 POMs but used by ~2; most outbound `RestClient`/`WebClient` instances have no timeouts. | e.g. `customer-service/.../HttpBookingClientAdapter.java:406-408`, `dispatch-engine/.../HttpJobOfferAdapter.java:35-37`, `api-gateway/.../GatewayBeansConfig.java:46-48` | Wrap every outbound call in `ResilientCall` with explicit timeouts. |
| **Medium** | Blocking `Thread.sleep` retries inside DB transactions and Kafka listener threads (payment invoice/wallet retries, notification backoff, shared consumer 3×5 s, outbox relay up to 243 s per event). | `PaymentService.java:216-234`, `NotificationDeliveryService.java:162-170`, `IdempotentKafkaConsumer.java:122-128`, `OutboxRelayService.java:85-109` | Persist `nextAttemptAt` and reschedule instead of sleeping. |
| **Medium** | Production paths backed by in-memory or logging stubs: pricing parameters and coupons, media/document storage (bytes discarded), SMS/email/push, payment gateways, wallet, refunds, geocoding, admin config, dispatch weights, presence. | see per-area lists | Track each stub as an open task; several are silent data loss today. |
| **Medium** | Duplicate default ports (8085, 8087, 8089, 8090). | `application.yml` of 8 services | Assign unique defaults. |
| **Low** | Copy-pasted `WebSecurityConfig`, `GlobalExceptionHandler`, exception classes, `LocalAesKmsAdapter`, `currentUser()` helpers across services; no root aggregator POM; JaCoCo gate enforced in only some modules. | all services | Move to shared starters and a parent POM. |

### 8.2 Authentication, gateway, outbox (shared libs, api-gateway, auth-service, outbox-processor)

| Sev | Finding | Where |
|-----|---------|-------|
| **Critical** | Self-service ADMIN registration: `resolveRole()` accepts any `Role` from the request; `completeRegistration` adds the role to existing accounts. | `auth-service/.../RegistrationService.java:148-171` |
| **High** | Social login audience check silently disabled: empty `audience` default → `requireAudience("")` is dropped by jjwt, so any Google/Apple ID token for any app is accepted. | `auth-service/application.yml:46,50`, `OidcIdentityVerifier.java:56` |
| **High** | RBAC Ant matching on raw URI: `/admin;x/users` reaches the `/admin/users` handler unmatched. | `RbacEnforcementFilter.java:64` |
| **High** | Outbox processor has no `default_schema`, so it polls `public.outbox_event` while every producer writes to its own schema. Domain events are never published. | `outbox-processor/application.yml:4-14` |
| **High** | Relay sleeps in-line up to 243 s per event and marks rows FAILED after a >4-minute broker outage; single scheduler thread stalls for hours on a 100-row batch. | `OutboxRelayService.java:85-109` |
| **Medium** | Refresh-token rotation is GET→check→SET across separate Redis calls; concurrent refreshes both succeed. Tokens stored in clear as Redis keys. | `TokenService.java:109-119`, `RedisRefreshTokenStore.java:33,49-57` |
| **Medium** | Gateway OTP throttle keys on a header/query param the client never sends (phone is in the JSON body), and uses `String.hashCode()`. | `OtpRateLimitGatewayFilter.java:60-95` |
| **Medium** | Introspection `WebClient` has no timeout; introspects every request; no caching. | `GatewayBeansConfig.java:46-48`, `JwtIntrospectionGatewayFilter.java:67` |
| **Medium** | `@Transactional` self-invocation in relay (`protected` methods called via `this`) is a no-op. | `OutboxRelayService.java:128-144` |
| **Medium** | Consumer dedupe not exactly-once: `handle()` and `recordProcessed()` in separate transactions. | `IdempotentKafkaConsumer.java:84-85` |
| **Medium** | Unknown-`kid` JWKS refresh on every request enables outbound-fetch amplification. | `JwksOidcKeyLocator.java:54-58` |
| **Medium** | Actuator `/metrics`, `/prometheus` exposed at root, unauthenticated. | `api-gateway/application.yml:120-125`, `auth-service/application.yml:62-67` |
| **Low** | Token accepted as `?token=` query param; `iss`/`aud` never checked on access tokens; `X-Forwarded-Proto` trusted; `/auth/logout` not in gateway public list so expired tokens cannot log out; WAF regexes on cookies/UA cause false positives; multiple relay instances double-publish (no `SKIP LOCKED`); `future.get()` without timeout. | various |

### 8.3 Booking flow (booking-service, dispatch-engine, pricing-engine, location-service)

| Sev | Finding | Where |
|-----|---------|-------|
| **Critical** | Dispatch calls `POST /internal/bookings/{id}/provider-accepted` and `/searching-failed`; booking-service has no such endpoints and requires authentication. Dispatch can never complete. | `dispatch-engine/.../HttpBookingTransitionAdapter.java:60,76` |
| **Critical** | `BookingCreated` schema mismatch: producer has no `customerLat/customerLon/requiredSkillTags`; consumer defaults to 0.0, so provider search runs at (0,0). The Pact was hand-written to match the consumer. | `booking-service/.../BookingCreatedEvent.java:11-20` vs `dispatch-engine/.../BookingCreatedEvent.java:14-21` |
| **Critical** | Location consumer listens on topic `booking.job-started`; relay publishes to `JobStarted`. Feeds are never terminated. | `JobStartedConsumer.java:30`, `outbox-processor/application.yml:49` |
| **Critical** | Pricing parameters and coupons exist only in memory; coupons can never be inserted; a fresh boot makes every estimate 404 → every booking create 503. | `InMemoryPricingParametersAdapter.java:17-32`, `InMemoryCouponAdapter.java:19-38` |
| **High** | Location `providerId` taken from request body rather than JWT; tracking view/stream do not check the caller. | `LocationController.java:48-79` |
| **High** | Dispatch consumer marks the event processed as soon as it is queued to the bulkhead; later failure leaves the booking stuck in SEARCHING_PROVIDER with no retry/DLQ. | `BookingCreatedConsumer.java:54-67` |
| **High** | Saga orchestrator rewraps `InvalidTransitionException` as 500 and runs inside the caller's transaction, so saga log rows and compensation are rolled back. | `BookingSagaOrchestrator.java:92-101` |
| **High** | `Booking.setProviderId` never called → `providerId` null in all provider events; `autoResolveApprovalTimeout` has no scheduler. | `Booking.java:147`, `JobExecutionService.java:258-274` |
| **High** | `@PathVariable UUID bookingId` without a name and no `-parameters` compiler flag → runtime resolution failure on Spring 6.1. | `LocationController.java:49,60,71`, `location-service/pom.xml:151-158` |
| **High** | Dispatch requests SEARCHING_PROVIDER → PROVIDER_ACCEPTED, which the state machine forbids (must pass through PROVIDER_ASSIGNED). | `DispatchService.java:161-162`, `BookingStateMachine.java:63-64` |
| **Medium** | Cancellation fee policy allows cancel from PROVIDER_ARRIVED/JOB_STARTED; state machine forbids it. No multipart size limits (1 MB default vs 50 MB policy). Destination resolver returns (0,0) so every ETA is wrong. Pricing/catalog HTTP calls inside `@Transactional`. Dispatch never checks for mid-flight cancellation; weights are process-local. Location rate limit is read-then-write; Redis/SSE updated before commit. Floor clamp yields a negative discount line. | `CancellationFeePolicy.java:37-40`, `LocationAdaptersConfig.java:64-68`, `BookingService.java:87-128`, `DispatchService.java:94-107`, `LocationService.java:95-157`, `PriceBreakdown.java:138-144` |
| **Low** | Null check after dereference in `MediaService.java:72-74`; unhandled `Instant.parse`/`UUID.fromString` on user input; assigned-UUID entities without `Persistable` cause SELECT+INSERT; whole catalog fetched per booking; `distanceKm`/`surgeActive` never sent to pricing; reference generator check-then-insert race; `SseEmitter(0L)` with process-local registry. | various |

### 8.4 Commerce (payment, invoice, promotion, catalog, provider, customer)

| Sev | Finding | Where |
|-----|---------|-------|
| **Critical** | Payment callback HMAC covers only `payload`; outcome, failure reason, transaction id and gateway id come from the unsigned request and are never bound to the payload. One valid signed payload marks any PENDING transaction SUCCESS. | `PaymentController.java:60-66`, `PaymentService.java:187-194` |
| **High** | Refund executes at the gateway before the amount is validated; an over-limit refund sends money then rolls back the record. No refund idempotency key. | `PaymentService.java:281,296` |
| **High** | Customer profile looked up by `findById(customerId)` but the row id is random and the customer id is `userId`; second profile PUT → unique violation, deletion always 404, anonymization never runs. Tests mock it away. | `CustomerProfileService.java:80-81,190,223` |
| **High** | Concurrent first payment requests both pass the DB check and charge twice; Redis reservation dangles on rollback. | `PaymentService.java:104-139` |
| **Medium** | Coupon cancel decrements `totalUsed` even when the user's count is already 0 (drift → over-redemption). Retry loop misses `CannotSerializeTransactionException` and `DataIntegrityViolationException`. | `CouponCounterTransaction.java:333-334`, `CouponService.java:160,193` |
| **Medium** | `failSettlement` loses the FAILED audit row if wallet reversal throws. Bank ref is double-encrypted when omitted. Provider settlements have no completion path; `creditJobEarning`/`setBankAccount` have no entry point so `bankAccountVerified` can never be true. | `PaymentService.java:340-352`, `ProviderService.java:253-282` |
| **Medium** | Only invoice storage adapter is in-memory (PDFs vanish, `backend=aws` fails to start); catalog deletion blockers stub always says "none" so referenced categories are hard-deleted. | `InMemoryInvoiceStorageAdapter.java:25-26`, `StubCategoryDependencyAdapter.java:457-460` |
| **Low** | Cache invalidated before commit; `Clock.systemDefaultZone()` for coupon expiry; unused Redis starters fail readiness; 15 s synchronous notify inside Kafka listener; payment "retry" only bumps a counter; profile photo validated then discarded, `photoUrl` trusted from client. | various |

### 8.5 Support services (notification, chat, complaint, rating, reporting, verification, admin)

| Sev | Finding | Where |
|-----|---------|-------|
| **High** | Verification endpoints have no ownership check: anyone can upload documents for, or read the audit trail of, any provider. | `VerificationController.java:49-64` |
| **High** | Review author is taken from the prompt, not the caller: any user can post a review on any booking as the customer. | `ReviewService.java:138-151,175-183` |
| **High** | STOMP clients can `SEND` directly to `/topic/chat/{bookingId}`; interceptor only checks SUBSCRIBE. | `WebSocketConfig.java:24`, `WebSocketAuthorizationConfig.java:41` |
| **High** | Complaint refund has no state precondition, records nothing, and calls the external port inside the transaction → repeated refunds. | `ComplaintService.java:206-232` |
| **Medium** | Complaint status changes are unvalidated (RESOLVED→IN_PROGRESS allowed, closed re-closed and re-published); booking existence/ownership never checked; `providerId` from body. | `ComplaintService.java:144-153,270-300`, `ComplaintController.java:56-57` |
| **Medium** | PII (`mobileNumber`, `emailAddress`) in Kafka payloads and forwarded verbatim to DLQ topics. | `LifecycleEventPayload.java:32-34`, `IdempotentKafkaConsumer.java:100` |
| **Medium** | Chat presence never updated → every message triggers a push. Fraud IP trusts `X-Forwarded-For` first hop. Two EAGER `List` bags on `Verification` → `MultipleBagFetchException` at boot (no JPA test to catch it). Admin dispatch weights/system config in-memory only while audit log says applied. Fraud SD rule flags any deviation when history is uniform. | `ChatService.java:134`, `ReviewController.java:88-95`, `Verification.java:54-61`, `DispatchWeightsStore.java:16`, `FraudDetector.java:85-88` |
| **Low** | No multipart limits/allowlist on document upload; RBAC rules registered at `ApplicationReadyEvent` (brief open window); `findAll()` for stats; hand-rolled PDF has no xref table; CSV without formula escaping; duplicate-review check-then-insert; WebSocket delivered before commit; handshake requires a Bearer header browsers cannot set. | various |

### 8.6 Frontends

| Sev | Finding | Where |
|-----|---------|-------|
| **Critical** | Admin login calls `/auth/login/otp` and `/auth/login/verify`; the backend only has `/auth/register/*` and `/auth/login/social`. Admin login always 404s. | `admin-portal/src/features/auth/api.ts:66,72` |
| **High** | Sessions never survive a reload: rehydrate sets `isAuthenticated` from the refresh token but nobody ever calls `/auth/token/refresh`; the first request 401s and the user is bounced to login. | `*/src/stores/authStore.ts:99-109`, `api/client.ts` |
| **High** | Live-tracking `EventSource` is torn down and recreated every render (fresh `queryKey` array in effect deps, 1 s ticker). | `customer-app/src/features/tracking/hooks.ts:77,133` |
| **High** | nginx and Vite proxies lack WebSocket upgrade headers and `proxy_buffering off`; chat WS cannot upgrade and SSE is buffered. | `customer-app/nginx.conf:55-60`, `vite.config.ts:42-45` |
| **High** | `.dockerignore` starts with a UTF-8 BOM so `node_modules` is not ignored and the host tree is copied into the image. | `customer-app/.dockerignore:1` |
| **Medium** | Admin routes only require "authenticated": any customer lands in the admin shell. Access token in SSE/WS query string. Logout never calls `/auth/logout`. Two incompatible status enums in provider-app. No `VITE_*` build args so Google sign-in is always off in Docker. No security headers; verification iframe without `sandbox`. | `admin-portal/src/router.tsx:40-159`, `customer-app/src/lib/realtime.ts:33,48`, `provider-app/src/features/dashboard/api.ts:21-22`, `*/nginx.conf`, `verification/DocumentViewer.tsx:71-90` |
| **Low** | Clickable table rows not keyboard-accessible; lockout parsed from error text; resend disabled for 5 minutes; hard-coded "Ara, Bihar" service area and a dead notification badge; no chat reconnect; stray `tsc-errors.txt`; dead code (`setAccessToken`, admin social buttons). | various |

### 8.7 Infrastructure and CI/CD

| Sev | Finding | Where |
|-----|---------|-------|
| **Critical** | Pipeline defaults every job to `hello-world-service`, which does not exist. Pushes to `main` test/build/deploy nothing real. | `ci.yml:43,78,96,210,286,342`, `cd-production.yml:72,182` |
| **Critical** | Docker build copies `target/*.jar` that CI never produces; `mvn test` cannot resolve `shared/` SNAPSHOTs (never installed, no aggregator). | `ci.yml:230-246`, `services/*/Dockerfile:5` |
| **Critical** | Helm probes `/actuator/health/*` on port 8080; services expose `/health/*` on 8081–8099. Every Helm-deployed pod fails its startup probe. | `helm/homefix-service/values.yaml:52,57,78,84,91` |
| **High** | EKS API endpoint public to `0.0.0.0/0`. | `infra/terraform/modules/eks/main.tf:84-85` |
| **High** | Committed `Secret` manifests with `PLACEHOLDER` credentials; Grafana admin password in tracked values; hard-coded MSK hostnames. | `infra/k8s/observability/keda-scaledobjects.yaml:22-35,...`, `prometheus-values.yaml:222` |
| **High** | Static AWS access keys in all jobs instead of OIDC; Trivy pinned to `@master` with `exit-code: 0`; Helm installed via `curl \| bash`. | `ci.yml:52,216-217,255-259` |
| **High** | Auto-promotion on `workflow_run` deploys `${{ github.sha }}` of the CD run, never the image CI built; smoke test path wrong and passes via `\|\| echo`. | `cd-production.yml:19-27,72-73,133` |
| **High** | API Gateway JWT authorizer issuer/audience cannot match auth-service tokens (`iss: homefix-auth`, no `aud`). | `infra/terraform/modules/api_gateway/main.tf:62-66` |
| **Medium** | Plaintext dev secrets repeated ~19 times in Compose; Spring Security DEBUG left on; `\|\| true` on coverage and Dockerfile checks; missing `Dockerfile.base`; empty S3 backend block; `automountServiceAccountToken: true`; images pinned by tag not digest; frontend nginx runs as root; K8s HPAs target Deployment names the chart never produces; two inconsistent namespace layouts. | various |
| **Low** | `your-org` placeholder in `Chart.yaml:18`; personal `.vscode/settings.json`; tracked scratch files `docker/_probe.txt`, `flow.txt`, `out.txt`, `p.txt`, `st.txt`, `smoke-result.txt` (which records 4 failing smoke checks), `run.ps1`; `.jqwik-database` files in three services; `customer-app/src/features/catalog/tsc-errors.txt`. | various |

---

## 9. Test coverage

**Backend (244 test files).** Coverage of pure domain logic is good and in places excellent: state machines, pricing invariants, coupon limits, invoice numbering, OTP lifecycle, refresh rotation, outbox atomicity, consumer retry/DLQ, resilience wrapper, PII scrubber, fraud detection, retry schedules. jqwik property tests exist in at least nine modules, Pact consumer/provider tests in four, embedded-Kafka ITs in notification and outbox-processor, and an H2 end-to-end IT in booking.

Systematic gaps:

- **No test anywhere exercises a real `SecurityFilterChain` with the shared RBAC filter**, so the missing authorization is invisible to the suite.
- **No ownership/authorization tests** in any service.
- **No JPA/DB slice tests** in chat, complaint, rating, verification, admin, payment, promotion, catalog, provider, customer (in-memory fakes throughout). Mapping bugs (EAGER bags, `findById` vs `findByUserId`) and the absence of tables go undetected.
- **Contract tests are hand-aligned to the consumer**, not generated from the producer, so the `BookingCreated` mismatch and the non-existent internal booking endpoints pass Pact.
- No concurrency tests against real Redis/Postgres (refresh rotation, payment idempotency, coupon counters, location rate limit).
- No Testcontainers usage at all.
- JaCoCo `check` gate exists in only some modules; CI's coverage step is `|| true`.

**Frontend.** Zero tests, no test runner configured. Highest-value targets: phone normalisation (two divergent `toE164` implementations), lockout parsing, booking/coupon zod schemas, dispatch weight validation, the axios 401 path, `RequireAuth` role gating, SSE/WS hook lifecycles, and one OTP-login E2E per app (which would have caught the admin 404 and the reload logout).

---

## 10. Technical debt summary

1. **No monorepo structure.** 24 standalone POMs with copy-pasted `dependencyManagement`/plugins; three frontend forks with ~40 identical files already drifting. A parent POM, a `shared-web` starter (security config, exception handler, principal helper, KMS adapter) and a frontend workspace with `@homefix/api` + `@homefix/ui` would remove most duplication.
2. **Stubs on production paths** with no tracking: payment gateways, wallet, refunds, SMS/email/push, S3/media storage (bytes discarded), background checks, geocoding, pricing parameter/coupon persistence, admin config, presence, analytics store, invoice storage. Several are silent data loss.
3. **Three retry mechanisms** (payment `Retries`, invoice `Sleeper`, shared resilience lib) with the shared one mostly unused.
4. **Dead/unused code:** emergency SLA properties, `Booking.setProviderId`, `autoResolveApprovalTimeout`, `PriceEstimate.COMPONENT_LABELS`, `authStore.setAccessToken`, admin social login buttons, catalog API functions against non-existent endpoints.
5. **Duplicate contracts:** two `Settlement` models (payment vs provider), two `PaymentCompletedEvent` records, two `BookingCreatedEvent` records, two booking-status enums in provider-app. A shared event-contract module is needed.
6. **Config drift:** vendor selectors (`sms-provider: twilio`, `backend=aws`) with no matching adapters break startup; unused Redis starters fail readiness in promotion, complaint, rating, invoice.
7. **Docs:** Javadoc repeatedly claims RBAC is "enforced upstream"; adapter docs describe endpoints that do not exist; admin README describes the customer layout; `tasks.md` marks unfinished work complete; no per-service README or OpenAPI.
8. **Repo hygiene:** scratch output files under `docker/`, `.jqwik-database` files, `tsc-errors.txt`, personal IDE settings, and the leaked JWT in history.

---

## 11. Remediation roadmap

### Phase 0 — Immediate (security, hours)

1. Rotate the JWT signing secret associated with the leaked `tok.tmp`; consider rewriting the `1b5656d` commit if the repo is public.
2. Restrict self-registration roles to `CUSTOMER`/`SERVICE_PROVIDER` (`RegistrationService.resolveRole`).
3. Remove all default secret fallbacks from `application.yml` so services fail to start without env vars.
4. Fail startup when OIDC `audience` is blank.

### Phase 1 — Make it correct (1–2 weeks)

5. Configure RBAC in every service (deny-by-default for `/admin/**`, agent roles for complaint/review moderation, provider roles for job milestones) and add a security-chain test per service.
6. Add ownership assertions (JWT subject vs path id) via a shared helper; cover with tests.
7. Fix the booking flow: add the internal booking transition endpoints with service-to-service auth, align `BookingCreated` fields, fix the dispatch transition sequence, fix the `JobStarted` topic name, set `default_schema` handling in outbox-processor (per-schema instances or multi-schema polling), and generate Pacts from producers.
8. Fix the payment callback (parse the signed payload, bind transaction id/amount/status, add replay protection), validate refunds before calling the gateway, and charge only after the PENDING row commits.
9. Fix `CustomerProfileService` lookups (`findByUserId`), the `Verification` EAGER bags, and the location `@PathVariable` names.
10. Add Flyway with baseline migrations for every schema, including `outbox_event` and `processed_event`.

### Phase 2 — Make it deployable (1–2 weeks)

11. Add a root aggregator POM; multi-stage Dockerfiles (or Jib) that build inside the image; CI matrix over `services/*` and `shared/*` instead of `hello-world-service`.
12. Fix Helm probe paths/ports and pass per-service values; reconcile the K8s manifest naming/namespaces with the chart; replace committed placeholder Secrets with ExternalSecrets.
13. Switch CI to OIDC role assumption, pin Trivy and fail on CRITICAL, remove `|| true`, pass the built image tag from CI to CD.
14. Restrict the EKS endpoint, configure the Terraform S3 backend, fix the API Gateway JWT issuer/audience.
15. Move Compose secrets to `.env` with a committed `.env.example`; delete scratch files under `docker/`.

### Phase 3 — Make it robust (ongoing)

16. Replace in-transaction sleeps with persisted `nextAttemptAt` scheduling (outbox relay, payment, notification, shared consumer); add `SKIP LOCKED` claiming to the relay.
17. Wrap all outbound HTTP in the shared resilience library with explicit timeouts; add a per-token introspection cache (or local JWT validation) at the gateway.
18. Persist pricing parameters/coupons, admin config, dispatch weights and chat presence; implement real storage adapters (S3) and at least one real SMS/email provider.
19. Frontend: implement silent refresh + 401 retry, fix admin login endpoints, memoise the tracking query key, add WebSocket proxy config, strip the `.dockerignore` BOM, gate admin routes by role, extract a shared workspace package, and add a test runner with the tests listed in Section 9.
20. Add Testcontainers-based DB/Redis/Kafka slice tests and concurrency tests for the idempotency paths.

---

## Appendix A — Strengths worth preserving

- Hexagonal ports/adapters with in-memory fakes in every service; business logic is testable without Spring.
- Transactional outbox with `Propagation.MANDATORY`, producer fail-fast on non-idempotent Kafka config, idempotent consumers with DLQ.
- State machines as single sources of truth with contiguous audit chains (booking, verification, payment, settlement).
- `BigDecimal` money with central rounding; discount clamping; invoice numbering that is genuinely race-safe.
- OTP hashes only, constant-time compares, refresh-token family replay detection, AES-GCM with random IVs, HMAC constant-time compare.
- Injected `Clock`, property-based tests for invariants, Pact scaffolding, JaCoCo gates on shared modules.
- Strict TypeScript and lint configuration, normalised API errors, correlation IDs end to end, non-root containers, hardened pod security context, encrypted-everything Terraform.

## Appendix B — Review method

Six parallel reviewers each read one slice of the repository in full (POMs, configuration, main sources, tests, infra) and reported verified findings with file and line references. Cross-cutting facts (versions, ports, migration strategy, test counts, git state) were checked directly. Findings were de-duplicated and merged into this document. No source files were modified during the review other than adding `.gitignore` and this file.
