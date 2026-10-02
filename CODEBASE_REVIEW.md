# HomeFix Platform — Codebase Review

**Date:** 2026-09-10, remediation status updated 2026-09-11
**Repository:** `KripaGIT009/home-fix` (branch `main`)
**Scope:** entire repository — 20 Spring Boot services, 4 shared libraries, 3 React apps, Docker Compose, Helm, Kubernetes manifests, Terraform, GitHub Actions, and the `.kiro` specifications.

> **Remediation in progress.** A first pass of fixes has landed since this review was written. See
> [Section 12](#12-remediation-status) for exactly what is fixed, what is partially fixed and what is
> untouched. The findings below are kept as originally written so the record of what was found stays
> intact; Section 12 is the authoritative current state.
>
> Supporting documents added alongside the fixes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for
> flow, sequence, data-model and state diagrams, [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md) for
> REST and event JSON contracts, and [docs/LOCAL_ACCESS.md](docs/LOCAL_ACCESS.md) for test users and
> URLs.

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

---

## 12. Remediation status

Updated 2026-09-11. Everything marked fixed was verified by a passing test run, with the result
noted. Nothing here is claimed on inspection alone.

### 12.1 Fixed and verified

| Finding | What changed | Verification |
|---------|--------------|--------------|
| **Self-service ADMIN registration** (8.2) | The role type now marks only customer and service-provider self-assignable, and registration refuses anything else before sending a code or creating a session. The role set also gained the four staff roles the rest of the platform already checked for: super admin, finance admin, dispatcher and support agent. | auth-service 99 tests pass, including 12 new escalation tests. Confirmed live against the running stack: asking for the admin role returns an invalid-role error. |
| **No RBAC rules configured** (8.1) | Eleven services gained a role-rule configuration class, registered during construction rather than on application-ready so the rules exist before the server accepts traffic. Public paths were deliberately left unruled, each with a pass-through regression test, because the filter runs inside the security chain and a rule there would turn a public path into a 401. | Per-service suites all pass: catalog 28, pricing 88, payment 57, invoice 38, promotion 58, provider 55, customer 45, verification 63, complaint 49, rating 60, chat 67. |
| **No object-level ownership checks** (8.1) | A caller-identity helper was added to provider, customer, verification, invoice, payment and promotion, asserting the caller is the subject named in the path or body unless they hold a staff role, using each service's existing exception and error envelope. | Covered by the suites above, with ownership tests per service. |
| **Review author impersonation** (8.5) | Submission persisted the prompt's reviewer and ignored the caller, so anyone could post a review on any booking as the real customer. Both submit paths now assert the caller matches, and persist the caller. | rating-review-service 60 tests pass. |
| **Customer profile read by the wrong column** (8.4) | Profile update, deletion and anonymisation looked up by primary key while the customer id lives in the user column, so a second update silently created a duplicate row and deletion always returned not-found. All three now look up by user. The test double that had hidden this was made stateful. | customer-service 45 tests pass, including a new test verified to fail against the old code. |
| **Default secrets in every config** (8.1) | The signing secret, database password, webhook secrets and encryption keys no longer have defaults in any of the 19 service configs, so a missing environment variable fails startup instead of shipping a public secret. Java-level fallbacks were removed too, including a customer key that decoded to 32 zero bytes. | auth 99, payment 57, customer 45 tests pass. Compose parses with the env file present and fails naming the variable when absent. |
| **Social login audience check disabled** (8.2) | A blank audience caused the check to be dropped entirely, so any Google or Apple token issued for any app was accepted. The verifier now rejects a blank issuer or audience at construction, and a provider that is not fully configured is simply not registered, so the platform still starts and returns its existing unsupported-provider error. | Proven with a temporary context test: unconfigured starts clean, blank audience disables the provider, fully configured registers. |
| **Outbox relay drained the wrong schema** (8.2) | This is why no domain event was ever published. Each producer wrote into its own schema while the relay polled the default one. The outbox tables are now pinned to a single shared schema that every producer and the relay agree on. Atomicity is unchanged: same database, same transaction. | shared outbox 30 tests pass, including the atomicity test. outbox-processor 16 tests pass. |
| **Dispatch could not call back into booking** (8.3) | The two internal endpoints dispatch had always called did not exist. They now do, addressed by booking id, guarded by a shared service credential, and both idempotent so the caller's retries are safe. | booking-service 141 tests pass, including 9 new transition tests and 6 new filter tests. |
| **Illegal transition requested by dispatch** (8.3) | Dispatch asked to jump straight from searching to accepted, which the state machine forbids. The new service walks the legal two-step path through assigned, in one transaction. | Covered by the booking tests above. |
| **Provider never recorded on a booking** (8.3) | The setter had no caller, so every downstream provider event carried a null provider. Acceptance now assigns it. | Covered by the booking tests above. |
| **Chat channels never activated** | The provider-accepted event omitted the customer the chat consumer requires, so every one of those events was dead-lettered. The event now carries the customer and the booking creation time. | dispatch-engine 53 tests pass. |
| **Location feed never terminated** (8.3) | The consumer listened on a hard-coded topic no producer wrote to. The topic is now configurable and defaults to the name the relay actually publishes. | location-service 23 tests pass. |
| **Admin portal login always failed** (8.6) | It called a login path that does not exist. It now uses the register endpoints, as the other two apps do. | All three apps lint, typecheck and build clean. |
| **Sessions lost on every page reload** (8.6) | No silent refresh existed, so the first call after a reload returned 401 and bounced the user to login. All three apps now refresh on rehydrate and retry once on a 401, with a single in-flight guard because refresh tokens are single-use. | As above. |
| **Logout never revoked server-side** (8.6) | All three apps now call logout before clearing local state. | As above. |
| **Live tracking reconnect loop** (8.6) | A freshly built query key in an effect dependency list tore down and recreated the location stream on every render, once a second. The key is memoised, and the chat socket gained reconnect with backoff. | As above. |
| **Upgrades blocked by the proxies** (8.6) | The nginx and Vite proxies had no upgrade headers and buffered responses, so the chat socket could not connect and server-sent events were buffered. Both now support upgrades with buffering off. | As above. |
| **Byte-order mark in the Docker ignore file** (8.6) | A BOM before the dependency directory meant it was never ignored, so the host tree was copied into the image. Stripped. | As above. |
| **Admin routes not role-gated** (8.6) | Every module now requires a staff role, reports also allow finance, system configuration stays super-admin only, and a non-staff token is refused at sign-in rather than landing in the admin shell. | As above. |
| **Missing security headers** (8.6) | Content-type, referrer and frame headers added to all three sites, and the document viewer iframe is now sandboxed. | As above. |
| **Conflicting booking status sets** (8.6) | The provider app carried two incompatible status enums for the same entity. Both now use one module matching the backend's eighteen states. | As above. |
| **Debug logging and scratch files** (8.7) | Security debug logging removed from two services in Compose, a stray compiler error file deleted, build info ignored. | Compose parses clean. |

### 12.2 Partially fixed

**Dispatch matched against coordinates of zero** (8.3). The consumer's event record used primitive
types, so absent coordinates silently became zero and every candidate was scored against a point in
the Gulf of Guinea. Those fields are now boxed, and the consumer refuses an event it cannot match on,
dead-lettering it with a reason that names the address and subcategory needing resolution. That turns
silent wrong behaviour into a loud, diagnosable failure, which is the right intermediate state, but it
does not yet make dispatch work.

Finishing it needs an enrichment step, and that needs a new endpoint elsewhere. Booking-service
publishes booking facts and has no client for either the address or the catalog's skill tags, and
customer-service exposes no way to read an address by id. Either add an internal address lookup and
let dispatch resolve both, or give booking-service the two clients and denormalise into the event. The
first keeps the event honest and is the better shape.

**Local Compose secrets** (8.7). Values moved out of the file into an environment file with a
committed example, and Compose now fails naming the variable when one is missing. The values are still
local development secrets, which is appropriate there. The production path through Secrets Manager is
unchanged and still unwired.

### 12.3 Not yet addressed

These remain exactly as described earlier, listed in the order I would take them.

1. **No database migrations** (8.1). Every service still validates against a schema nothing creates, and Compose still overrides to auto-update. Any deployment outside Compose fails at startup. This is now the largest single obstacle to deploying anything, and the new shared outbox schema is one more object a real migration must create.
2. **The pipeline builds and deploys nothing real** (8.7). It still defaults to a service that does not exist, still never packages the jars its images copy, still never installs the shared modules, and the chart still probes the wrong path and port.
3. **Payment callback outcome is unsigned** (8.4). The signature still covers only the payload while the outcome, failure reason and target transaction come from the unsigned request, on a public endpoint. This is the most serious remaining security finding.
4. **Refund ordering and idempotency** (8.4, 8.5). The payment refund still calls the gateway before validating the amount, and the complaint refund still has no status precondition and records nothing, so repeated calls refund repeatedly.
5. **Notifications cannot address anyone** (contracts). No producer emits a recipient or any contact field, so six of eleven lifecycle topics always dead-letter and the two that process have nothing to deliver to.
6. **Three topics have consumers but no producer**: provider-assigned, provider-rejected and booking-cancelled. The last matters most, since chat relies on it to close channels for cancelled bookings.
7. **Complaint events are unmapped** and land on a topic nothing reads.
8. **In-transaction sleeps** in the relay, payment and notification paths, and the relay's lack of row claiming, remain as described in 8.1 and 8.2.
9. **Production paths still backed by stubs**: payment gateways, wallet, storage that discards bytes, SMS, email, push, background checks, geocoding, and pricing parameters that live only in memory.
10. **The remaining items** in sections 8.2 through 8.7, including the role filter's raw-URI matching, refresh rotation atomicity, the gateway's uncached per-request introspection, and the eager-collection mapping on verification.

### 12.4 A note on the spec

The task list still marks all 46 implementation tasks complete. The work above, and everything in
12.3, contradicts that for the pipeline, deployment, authorization and integration tasks. Those should
be reopened before the checklist is used to judge readiness.

---

## 13. Second pass — 2026-09-12

Added while implementing password sign-in and running the full stack locally. Section 12
remains accurate; this section records what the run turned up that the first pass did not.

### 13.1 Added

**Username and password authentication.** The platform previously had exactly one way in —
an OTP to a mobile number — which is wrong for a staff console: operators sign in many times
a day, and the code has to be read out of a log file locally or an SMS in production.

- `POST /auth/login/password` authenticates an account that carries credentials and returns
  the same `TokenResponse` body every other authentication path returns. It never creates an
  account and the caller never names a role, so a password can authenticate but never
  escalate: the roles returned are the ones already stored.
- `UserAccount` gained a nullable unique `username` and a `password_hash`, both null for the
  OTP-only and social accounts that already existed. Hashing goes through the existing
  cost-12 bcrypt encoder, which had been configured but had no caller.
- An unknown username, an account with no password set, and a wrong password all return one
  401 `INVALID_CREDENTIALS`, and the encoder is run against a dummy hash in the first two
  cases so response time does not separate them either.
- Five consecutive failures lock the username for 30 minutes with a 429 and `Retry-After`,
  mirroring the OTP lockout. State lives in Redis under `auth:pwd:*` with TTL-based expiry,
  so a restart cannot clear a lock.
- The Admin Portal login screen now offers Password (default) and Mobile OTP. Both assert the
  session holds a staff role before storing it, as the OTP path already did.
- Verified: auth-service suite passes including 14 new tests; lockout confirmed live against
  the running stack (5th attempt → 429 `ACCOUNT_LOCKED`); sign-in confirmed end-to-end in a
  real browser through the portal's nginx proxy.

**Startup account seeding.** Staff roles are correctly not self-assignable, which left a
fresh database with no account able to open the Admin Portal at all; the gap was filled by a
shell script issuing SQL inserts after the fact. `DevAccountSeeder` now creates the nine
documented test accounts at startup, off unless `homefix.auth.dev-seed.enabled` is true and
inert unless given a password that has no default. It is idempotent and preserves account
ids, so re-running never orphans data that references an account.

### 13.2 Found — the Admin Portal is a console over endpoints that mostly do not exist

This is the largest finding of the second pass and it is not in section 12.

The Admin Portal calls 15 `GET /admin/**` endpoints. **Two answer 200. Thirteen do not.**
Probed through the gateway with a `SUPER_ADMIN` token against the running stack:

| Portal calls | Status | What actually exists |
|---|---|---|
| `/admin/dashboard` | **200** | admin-service `DashboardController` |
| `/admin/system-config` | **200** | admin-service `SystemConfigurationController` |
| `/admin/audit-logs` | 400 | exists, but rejects the portal's parameters |
| `/admin/users` | 404 | nothing: no user-list endpoint anywhere on the platform |
| `/admin/providers` | 404 | provider-service exposes only `/providers/{id}` — no list |
| `/admin/bookings` | 404 | booking-service `/bookings` is POST-only (GET → 405) |
| `/admin/payments` | 404 | payment-service `/payments` is POST-only (GET → 405) |
| `/admin/complaints` | 404 | complaint-service `/complaints` is POST-only (GET → 405) |
| `/admin/coupons` | 405 | promotion-service `/coupons` is POST-only (GET → 405) |
| `/admin/reviews` | 404 | rating-review `/reviews` GET → 500 |
| `/admin/reports/types` | 404 | reporting-service `/reports` is POST-only (GET → 405) |
| `/admin/notification-templates` | 404 | notification-service has no controller at all |
| `/admin/verification/queue` | 404 | admin-service has `/admin/verification-queue` — near miss |
| `/admin/dispatch/config` | 404 | admin-service has `/admin/dispatch/weights` — near miss |
| `/admin/pricing/config` | 404 | pricing-engine has `/admin/pricing/**`, no `/config` |

The two near misses are not one-line path corrections either:

- `/admin/verification-queue` returns `{providerId, submittedAt, documents[]}`; the portal
  expects `{providerId, displayName, mobileNumber, primarySkill, submittedAt, documentCount}`.
  It is also backed by `StubVerificationQueueAdapter`, which returns an empty list — the HTTP
  adapter over verification-service was never written.
- `/admin/dispatch/weights` returns five flat `*Weight` numbers; the portal expects a nested
  `weights` object plus four radius and timeout parameters the backend does not hold.

So this is not drift to be patched at the path level. **Eleven of the sixteen admin modules
have no backend**, and the shape mismatches on the other two show the UI was built against
`design.md` rather than against anything that was implemented. The domain services are
genuinely missing the list/search/filter endpoints an operations console needs — every one of
them was built as a command surface (POST to act on a known id) with no query side.

Fixing it properly means, per module, a paged and filtered list endpoint on the owning
service, an admin-service route or gateway rule, and RBAC rules on the new paths. That is a
substantial piece of backend work, not a cleanup. It should be planned as such, and until it
is, the portal's module screens will keep showing "Not Found" — which is at least honest,
since the shared query client surfaces the 404 rather than rendering an empty table that
looks like real data.

`.kiro/specs/tasks.md` marks the admin tasks complete. They are not.

### 13.3 Operational note

Running all 20 services plus Kafka and Postgres needs more memory than Docker Desktop is
given by default. On a 16 GB host the stack starts but thrashes: containers intermittently
stop answering, buildkit dies mid-build with `Unavailable: EOF`, and the Docker API itself
returns 500s. rating-review-service failed its first start outright on a Kafka DNS race
during the startup stampede and came up on restart.

Raise the WSL memory ceiling in `%UserProfile%\.wslconfig` before judging anything about the
stack's behaviour:

```ini
[wsl2]
memory=12GB
```

Stopping the four services no admin module calls — chat, location, outbox-processor and
invoice — was enough to make the portal respond consistently on a 16 GB machine.

> **Superseded by section 14.4.** The stack now carries per-service memory limits and JVM
> heap caps, and the whole thing runs together on a 16 GB host. Running a reduced slice is no
> longer necessary.

One diagnostic point from this pass is worth keeping: a readiness probe from the host is not
evidence a service is down. Under load Docker's port forwarding drops connections while the
service answers fine on the Compose network, so confirm from inside it before concluding
anything is broken.

---

## 14. Third pass — 2026-09-13

Found by restarting the whole stack and watching what broke. All three are latent bugs that
only surface when a container is recreated — which `docker compose up` does routinely — so
none of them would show up in a stack that is started once and left alone.

### 14.1 The API Gateway cached the Auth Service's IP address

**The most serious of the three.** The gateway's introspection `WebClient` was
`WebClient.builder().build()`, whose Reactor Netty resolver caches a DNS answer for the TTL
of the record. Docker's embedded DNS hands out 600 seconds. Recreating auth-service therefore
left the gateway introspecting a dead address for ten minutes.

Because a transport failure is — correctly — mapped to "inactive" so a downstream outage
cannot be used to bypass authentication, the symptom was not an error. It was **every
authenticated request on the platform returning 401** while auth-service sat there perfectly
healthy, answering `/auth/introspect` on the command line. Nothing in the logs said "wrong
address"; only `Token introspection call failed: WebClientRequestException` once per request.

Fixed by resolving through the JDK resolver (`DefaultAddressResolverGroup`), which honours
`networkaddress.cache.ttl`, and pinning that to 10s for the gateway in Compose. The same
change adds the introspection response timeout that section 8.2 asked for: every authenticated
request is introspected, so an unbounded call there does not delay one request, it holds
gateway connections until they are exhausted.

Verified live: auth-service was forced onto a new address (`.22` to `.28`, with a placeholder
container parked on the old one) and the gateway followed it with no restart. 32 gateway tests
pass.

### 14.2 All three SPAs cached their backends' IP addresses

The same bug one layer out. `proxy_pass http://auth-service:8081/auth/;` with a literal
hostname is resolved once, at worker start, and cached for the life of the process — so
recreating auth-service or the gateway meant every SPA served 502 until nginx was restarted.
Observed directly: nginx was connecting to `172.20.0.15` while auth-service was on
`172.20.0.19`.

Fixed in all three `nginx.conf` files by adding Docker's embedded resolver and naming each
upstream in a variable, which is what forces nginx to re-resolve; the `/api` prefix stripping
each location did through `proxy_pass` is preserved with an explicit `rewrite`.

Verified live: after moving auth-service to a new address, all three SPAs proxied correctly
without a restart.

### 14.3 Every page load fired one unauthenticated request and rotated the refresh token twice

The access token is deliberately memory-only, so after a reload the store knows it is
authenticated (it has a refresh token) but holds no access token. `RequireAuth` rendered
immediately on that flag, so each screen's queries fired with no `Authorization` header,
401'd, and were retried behind a refresh.

Section 12.1 recorded this as fixed. It was half-fixed: the retry stops the user being bounced
to login, which was the visible symptom, but the wasted round trip remained. Worse, the
rehydration refresh in `onRehydrateStorage` called the store action directly, bypassing the
token bridge that exists to collapse concurrent refreshes onto one request. It could not
dedupe against the 401-driven refresh the unauthenticated query triggered, so a page load
rotated the single-use refresh token twice.

Fixed with an `isHydrating` flag that the route guard waits on, and by moving the hydration
refresh out of `onRehydrateStorage` to after the bridge registrations, routed through the
bridge so it collapses with any concurrent 401-driven refresh. Refresh tokens are single-use
with replay detection, so two live refreshes presenting the same token revoke the whole family.

Applied to all three SPAs — the stores and guards are near-identical copies, and customer-app
and provider-app carried the same bug verbatim.

Measured in a real browser, per page load:

| | before | after |
|---|---|---|
| unauthenticated 401s | 1 | 0 |
| refresh-token rotations | 2 | 1 |
| first request authenticated | no | yes |

### 14.4 Compose had no restart policies and no memory limits

Neither existed anywhere in the file. Two consequences, both observed:

- A service that lost a startup race stayed down permanently. rating-review-service failed
  twice on a Kafka DNS race during the startup stampede and needed a manual restart each time.
  All 26 services now carry a bounded `restart: "on-failure:5"`.
- With no container limit each JVM sized its max heap at 25% of the whole VM — about 1.9 GB
  each against a measured steady state of ~300 MB. Twenty of them had no reason to collect
  early and between them exhausted the host. Every Java service now declares `mem_limit: 448m`
  with a matching `MaxRAMPercentage`, and Kafka's 1 GB default heap is capped at 512 MB.

With both in place and the WSL ceiling raised to 11 GB, all 20 services, 3 SPAs and the
infrastructure run together on a 16 GB host using ~6.1 GB, with no container needing a restart.
Section 13.3's advice to run a reduced slice no longer applies; see `docs/LOCAL_ACCESS.md`.

---

## 15. Fourth pass — 2026-09-20

Found by starting the stack from cold on a machine where Docker was not even running, then
running the two check scripts the repository ships. The stack itself came up cleanly — all 26
containers healthy on the first attempt at ~5.9 GB, so section 14.4's memory work holds — but
both scripts failed, and one of the failures was hiding a defect that broke a third of the
platform's HTTP surface.

### 15.1 Eight services 500'd on any request with a path variable

`javac` discards parameter names unless it is given `-parameters`. Spring resolves an
`@PathVariable`/`@RequestParam` that does not name its binding by reflecting on the parameter
name, so without the flag those handlers throw at request time:

```
java.lang.IllegalArgumentException: Name for argument of type [java.util.UUID] not specified,
and parameter name information not available via reflection.
```

Not a compile error, not a startup error — a 500 on the first request to reach the handler.

The flag is missing because every service imports `spring-boot-dependencies` as a BOM rather
than inheriting `spring-boot-starter-parent`, and the parent is what normally sets
`maven.compiler.parameters`. No pom in the repository set it, so this was latent everywhere and
live in the eight services that have an unnamed binding:

| Service | Unnamed bindings | Caught by the smoke script |
|---|---|---|
| booking-service | 2 of 24 | no |
| chat-service | 2 of 2 | yes |
| complaint-service | 3 of 3 | no |
| invoice-service | 4 of 6 | yes |
| location-service | 3 of 3 | yes |
| promotion-service | 4 of 4 | yes |
| rating-review-service | 2 of 2 | no |
| verification-service | 1 of 9 | no |

The smoke script only reached four of them; the other four's affected endpoints were answered
earlier in the chain by a 400 or a 403, so the bug sat behind a passing test.

Fixed by adding `<parameters>true</parameters>` to the compiler plugin in all 24 poms —
services and shared libraries — rather than naming the bindings at each of the 21 call sites,
so a newly written handler cannot reintroduce it. Verified in the bytecode (`MethodParameters`
is now emitted) and against the running stack: the five endpoints that returned 500 now return
the correct 200 or 404.

### 15.2 No domain event had ever been published — the relay died on every poll

`verify-outbox-flow.sh` reported rows stuck in `PENDING` with `last_error=(none)` and an empty
Kafka topic. The relay was logging, once per poll cycle and forever:

```
Outbox poll cycle failed: Unable to access lob stream
```

`OutboxEventEntity.payload` was mapped `@Lob String`. On PostgreSQL Hibernate renders that as
`oid` — a pointer into `pg_largeobject`, not the JSON — and reads it back through the
large-object API, whose stream is only valid inside the transaction that opened it. The
confirmed column type was `oid`, and the payloads were intact but only reachable via `lo_get`.

The failure mode is worse than an outage: the poller caught the exception per cycle, so rows
accumulated as `PENDING` with **nothing written to `last_error`** and no row ever reaching
`FAILED`. Every health check was green. Section 12's "the relay and the producers now agree on
one outbox schema, so events are actually published" was true about the schema and wrong about
the outcome — the events still went nowhere, for an unrelated reason one layer down.

Fixed by mapping the column as text (`@JdbcTypeCode(SqlTypes.LONGVARCHAR)` plus
`columnDefinition = "text"`). A database created from scratch now gets the right type from
`ddl-auto`. An existing volume does not, because `ddl-auto: update` never changes a column
type, so `docker/migrate-outbox-payload.sql` converts in place — it is idempotent, preserves
pending rows, and unlinks the orphaned large objects instead of leaking them.

This is a good argument for the migrations that section 8.1 asks for: the fix needed a data
migration, and there is nowhere in this repository for one to live.

### 15.3 Both check scripts reported failures that were their own

Three defects, all of which made a working system look broken or a broken one look fine:

- **`seed-pricing.sh` authenticated as a customer.** `PUT /admin/pricing/parameters` requires
  ADMIN, which the authorization hardening in section 12 introduced; the script still
  registered a throwaway OTP number, which self-registers as CUSTOMER. Every subcategory
  403'd. It now signs in as the seeded `admin` account.
- **…and reported success anyway.** It counted attempts, not outcomes, so a run in which all
  eight PUTs failed still signed off with `Seeded pricing parameters for 8 subcategories`. It
  now counts each separately, prints the failures, and exits non-zero.
- **`smoke-flows.sh` tested a provider with a customer token.** `login()` never sent a role,
  and `/auth/register/otp` defaults to CUSTOMER, so the three provider and verification checks
  were failing on role enforcement rather than exercising the endpoint. It now passes
  `SERVICE_PROVIDER` — which must go on the OTP request, not the verification, because that is
  where the role is bound to the session.
- **…and could not be run twice in an hour.** The three SPA proxy checks all requested an OTP
  for one hard-coded number against a five-per-hour limit, so a second run inside the hour
  failed with 429 against a proxy that was working. Each app now uses a fresh number per run.

### 15.4 The Kafka assertion could not run on Windows at all

`verify-outbox-flow.sh` reported `topic empty or unreachable` while the topic held the
messages and the dispatch consumer was visibly reacting to them. Git Bash rewrites an argument
that looks like an absolute POSIX path into a Windows one, so the in-container
`/opt/kafka/bin/kafka-console-consumer.sh` reached `docker exec` as
`C:/Program Files/Git/opt/kafka/bin/kafka-console-consumer.sh`. The consumer never started,
its stderr was redirected to `/dev/null`, and the check blamed Kafka.

Fixed with `export MSYS_NO_PATHCONV=1`, which is inert on Linux and macOS. Worth remembering
for any future `docker exec` with an absolute path: on this platform the failure is silent and
looks like the thing being tested.

After all of the above, both scripts pass in full from a cold start: `smoke-flows.sh` 77/77
(was 69/77) and `verify-outbox-flow.sh` 12/12 (was 9/12), with all 26 containers healthy at
~6.4 GB and every outbox row reaching PUBLISHED.

---

## 16. Fifth pass — 2026-10-02

The working tree held a large uncommitted change set (about 7,800 lines across 197 files) that
closed most of section 12.3: signed payment callbacks, refund idempotency, notification
recipients, producers for the three orphaned topics, dispatch enrichment, outbox row claiming,
atomic refresh rotation, a real CI/CD pipeline and per-service Helm values. It built and passed
on all 24 modules, but nobody had reviewed it. Four parallel reviews of that change set, a
second build, and running the stack end to end turned up what follows. Everything listed as
fixed was verified by a test run or against the running stack; section 16.10 has the numbers.

The run also moved the local stack onto the Postgres, Kafka and Redis installed on the
developer machine instead of containers (16.8).

### 16.1 Provider matching could never succeed — now it does

The enrichment work made dispatch resolve coordinates and skill tags, but two endpoints it then
called did not exist anywhere: provider-service's `GET /internal/providers/eligible` and
notification-service's `POST /internal/offers`. The provider app's offer screens called a third
set (`/bookings/{id}/offer|accept|decline`) that booking-service never had. So every booking
with a resolvable address ended in `SEARCHING_FAILED`, and one without an address sat in
`SEARCHING_PROVIDER` forever: the unresolvable event was retried, dead-lettered, and nothing
moved the booking on.

- **Eligibility.** Provider profiles gained a base location (`base_latitude`, `base_longitude`)
  and provider-service now answers `/internal/providers/eligible`: verification APPROVED
  (one batch call to a new verification-service `/internal/verifications/approved`), not under
  review, a matching skill tag (Requirement 8.2), within `min(radius, own radius)` by haversine,
  emergency-available when required, available now. Scores are normalised to [0, 1]. Both
  internal endpoints are guarded by the shared `X-Internal-Api-Key`, which dispatch now sends.
- **Offers.** Dispatch owns them, in Redis: an offer records the window, the provider answers
  through `GET /dispatch/offers`, `GET|POST /dispatch/offers/{bookingId}[/accept|/decline]`
  (routed by the gateway, SERVICE_PROVIDER only, matched to the JWT subject), and decisions are
  WATCH/MULTI/EXEC compare-and-set so a late or double answer is refused. The provider app's
  dashboard polls for offers and its Job Request screen answers them.
- **Dead ends closed.** An unresolvable booking is marked `SEARCHING_FAILED` instead of being
  dead-lettered; a `BookingCancelled` stops an in-flight search and withdraws the pending offer;
  a 409 from booking-service ends the run; and chat writes a tombstone when a cancellation
  arrives before the channel exists, so a late `ProviderAccepted` cannot open a chat on a
  cancelled booking.

**Found only by running it:** every provider acceptance answered `409 OFFER_ALREADY_DECIDED`
even though it had succeeded. The compare-and-set queued a lone `HMSET`, whose status reply
`RedisTemplate.exec()` drops, so a committed transaction came back as an empty list — the
code's signal for "discarded by a concurrent write". It retried, found its own write, and
refused it. The unit test had stubbed `exec()` with a non-empty list and could not see this.
The transaction now also queues `HLEN`, whose integer reply is always kept.

### 16.2 The customer booking flow lost bookings, addresses and coupons

- **Scheduled bookings were never confirmed.** Only `POST /bookings/{ref}/confirmation` moves
  a scheduled booking out of `CREATED` and publishes `BookingCreated`; the customer app never
  called it. It now confirms after creating, and a failed confirmation is retried against the
  same booking rather than creating another.
- **Bookings could be created without an address**, and therefore could never be dispatched:
  a typed address had no coordinates, save failures were swallowed, and every GPS booking
  saved a new address until the 10-address limit silently turned the rest into address-less
  bookings. `addressId` is now required by booking-service, the app reuses a matching saved
  address and surfaces save errors, and confirmation is blocked without coordinates.
- **Coupons were shown but never charged.** The estimate applied the coupon; booking creation
  dropped it and re-priced without it. `couponCode` now travels through booking creation to
  pricing.

### 16.3 Payments: a double-refund path and four smaller defects

- **A gateway timeout during a refund could refund twice.** The refund was marked FAILED on an
  unknown outcome and the caller told to retry with a new idempotency key — which minted a new
  gateway idempotency key too. Now an unknown outcome stays PENDING (`502
  REFUND_OUTCOME_UNKNOWN`), a same-key retry re-sends under the same refund id, and FINANCE
  can reconcile a stuck refund (`POST /payments/{tx}/refunds/{refund}/reconcile`).
- Amounts with more than two decimals were rounded by `numeric(12,2)` but sent unrounded to
  the gateway; they are now rejected.
- A fast callback could make the charge's final write lose an optimistic lock, returning 500
  for a successful charge and never saving the gateway reference; it now retries that write,
  and a refund without a gateway reference is refused before reaching the gateway.
- `POST /payments/{id}/retries` lacked the ownership check, so any customer could fail another
  customer's pending payment.
- The provider wallet credit ran after the SUCCESS commit with nothing recording that it was
  owed; a marker is now set in the same transaction and a sweeper re-sends owed credits.

### 16.4 Pricing parameters are durable; coupons have one owner

Pricing parameters lived only in memory (section 8.3), so every pricing-engine restart broke
every booking until `seed-pricing.sh` was re-run. They now persist in a `pricing` schema behind
the existing cache. Pricing's private copy of the coupon rules, which no API could ever
populate, is gone: coupons are quoted by promotion-service through an internal endpoint, so
there is one set of discount rules and error codes.

### 16.5 Messaging: four ways to lose or duplicate an event

- **Consumer dedupe was not atomic** (8.2): check, handle and record ran in separate
  transactions, so a failure between handling and recording re-ran the handler. The
  processed-event insert and the handler now share one transaction, insert first.
- **Dead-lettering did not wait for the send**, so a failed DLT write lost the event while the
  offset committed. It now waits and rethrows.
- **A broker outage of about eight minutes still marked events FAILED for good**: every
  timed-out publish spent an attempt. Retriable broker failures no longer count.
- **Skipped outbox rows stayed hidden until their lease expired**, and the lease check ignored
  `max.block.ms`. Skipped rows are now handed back, and the check includes it.
- Notification delivery-log rows now commit on their own, because the handler now runs inside
  the dedupe transaction: an SMS that went out must stay logged even if a later step fails.

### 16.6 Gateway and authentication

The introspection timeout now covers connecting and pool acquisition, not just the response
(an unreachable auth-service could still hold gateway requests for 30-45 s). Logout revokes the
whole refresh-token family, so a stolen, rotated token dies with the victim's logout. A startup
task removes the cleartext refresh-token keys the pre-hashing version left in Redis; holders of
those tokens sign in again.

### 16.7 Schema migrations exist (closes 8.1 and 12.3 item 1)

Every database-backed service now runs Flyway, and Hibernate only validates. Each `V1__baseline`
was generated with `pg_dump` from a database Hibernate had populated from the current entities,
then proven by applying all of them to an empty database and diffing the two dumps: identical
apart from PostgreSQL's own re-spelling of the same CHECK expressions. The shared outbox tables
ship as a repeatable migration inside `homefix-shared-outbox`, serialised across services with
an advisory lock. `baseline-on-migrate` lets a database created by the old `ddl-auto=update`
setup adopt the migrations without re-creating anything. Compose no longer forces
`ddl-auto=update`, so the local stack now uses the same mechanism as a deployment.

This was the largest single obstacle to deploying outside Compose. Every later schema change
needs a `V2__…`; the baselines must not be edited.

### 16.8 The local stack runs on the machine's own Postgres, Kafka and Redis

At the user's request the local run no longer starts infrastructure containers: services reach
PostgreSQL 18 (Windows service), Kafka 4.1 and Redis on the host through `host.docker.internal`.
AWS is unaffected: Helm already wires RDS, MSK and ElastiCache per environment. The containerised
infrastructure survives as an opt-in `docker-compose.infra.yml`. Setup is in
`docs/LOCAL_ACCESS.md` section 1.1. Three things are worth knowing:

- **Kafka needs a second listener.** The distribution advertises only `localhost:9092`, which
  inside a container means the container. A `DOCKER` listener on 9094 advertised as
  `host.docker.internal` fixes that.
- **Kafka on native Windows cannot delete segments.** Retention, compaction and topic deletion
  all rename memory-mapped files, which Windows refuses; Kafka then marks the whole log
  directory failed and shuts down. It did, twice, within a minute of starting. Retention and
  the log cleaner are now off for this broker, on a fresh data directory. Topic deletion must
  be avoided.
- **Redis is not installed yet.** The Memurai installer needs administrator rights the session
  did not have; a Redis container on port 6379 stands in until it is installed.

The seed and check scripts reach the host's Postgres and Kafka through a shared
`docker/local-infra.sh` instead of `docker exec`, and Compose's health checks now allow a 300 s
start period: on a cold start twenty CPU-bound JVMs need 3-5 minutes, and the old window made
`up --wait` report a healthy stack as failed.

### 16.9 Smaller fixes

- The customer app and admin portal read `body.code` from errors while the backend envelope
  uses `errorCode`, so every error code arrived as `UNKNOWN_ERROR`.
- The deploy workflow's Secret preflight ran without `pipefail`, so a missing Secret was
  reported as a list of missing keys.
- Helm values for pricing-engine (database, coupon credential) and promotion-service (internal
  key) were missing.
- `smoke-flows.sh` and `verify-outbox-flow.sh` booked without an address; the latter now
  asserts the whole match: offer, acceptance, `PROVIDER_ACCEPTED`, `ProviderAccepted`.

### 16.10 Verification

| Check | Result |
|---|---|
| All 24 modules, `mvn verify` / `install` | pass — 1,893 tests, 0 failures |
| Schema baselines vs. Hibernate-generated schema | identical (16.7) |
| Stack on local Postgres 18, Kafka 4.1, Redis | all 20 services healthy; Flyway baselined 18 schemas, validation passes |
| `smoke-flows.sh` | 78/78 |
| `verify-outbox-flow.sh` | 17/17, including dispatch match and provider acceptance |
| Cold restart (`down`, then `up -d --build --wait`) | pass — all 20 services healthy, 400 s including image builds |

### 16.11 Customers could not see their own bookings; the customer app was redesigned

Found from the user's screenshots after the restart. booking-service had **no read endpoints**:
the app's booking history (`GET /bookings/history`) and detail (`GET /bookings/{id}`) called
paths that did not exist, and the tracking screen, unable to learn the booking's status,
rendered "No location available yet for booking <uuid>" as a red error for a booking whose
search had in fact failed. Both endpoints now exist (customer, assigned provider or staff;
anyone else gets the same 404 as a missing booking; history is 1-based and paged), with a
`V2` migration for the history index — the first incremental migration, applied cleanly on
the live database.

The customer app was redesigned: a self-hosted typeface and a real type scale, a responsive
1200 px layout with a desktop top nav and a mobile tab bar instead of a phone column stranded
on a wide screen, a proper home page, a split sign-in with a six-box OTP input, and every
screen restyled. Two behaviours changed with it. Errors are humanised in one place — an
unreachable or restarting backend (network, 502/503/504) reads "We can't reach HomeFix right
now" instead of "Request failed with status code 502" — and tracking is status-aware, polling
the new detail endpoint: finding a professional, no professional available (with book-again),
assigned, on the way with the live map, in progress, done or cancelled. A missing location fix
is a waiting state, never an error. The Vite dev proxy also failed to strip `/api` the way
nginx does, so every gateway call 404'd under `npm run dev`; fixed.

### 16.12 Still open

1. **Admin portal back end** (13.2): eleven of sixteen modules call list endpoints that do not
   exist. This is a feature project — a paged query endpoint per owning service — not a fix.
2. **External providers are stubs**: payment gateways, SMS/email/push, document storage,
   background checks, geocoding.
3. **Coupons are never redeemed.** They are quoted and charged, but nothing calls
   promotion-service's redeem, so per-user and total limits are never consumed. Bookings also
   do not store the coupon, so a re-quote after parts are added drops it.
4. **No address listing.** customer-service cannot list a customer's saved addresses, so the
   app reuses only addresses saved from the same device.
5. **Push and email cannot be addressed**: no device tokens or email addresses reach
   notification-service. SMS and in-app work.
6. **Deployment topology**: the deploy workflow puts all services in one namespace, while
   Terraform creates six group namespaces with deny-all NetworkPolicies and a CPU quota the
   chart cannot fit. One of the two has to change before the first real deploy.
7. **CI coverage gate**: CI enforces 80% line coverage per service; several services may sit
   below it. Measure before relying on the pipeline.
8. **The leaked JWT in commit `1b5656d`** (section 11, Phase 0) — rotate the secret that
   signed it if that has not been done.
