# Implementation Plan: HomeFix Platform

## Overview

This implementation plan covers all 46 tasks required to build the HomeFix on-demand home services marketplace. The tasks are organized into four phases:

- **Phase 1** — AWS infrastructure, shared libraries, and CI/CD pipeline (Tasks 1–6)
- **Phase 2** — 19 microservices implemented in dependency order (Tasks 7–29)
- **Phase 3** — Customer App, Provider App, and Admin Portal frontends (Tasks 30–36)
- **Phase 4** — Unit tests, 27 property-based tests (jqwik), integration tests, and contract tests (Tasks 37–46)

Each task is sized for 1–3 developer days. Dependencies are explicit so tasks can be parallelized where possible.

## Tasks

### Phase 1 — Infrastructure & Shared Foundation

- [x] 1. Provision AWS core infrastructure with Terraform
  - Define Terraform modules for VPC, subnets (public/private), security groups, and IAM roles
  - Provision Amazon EKS cluster (multi-AZ) with node groups and IRSA service accounts
  - Provision Amazon RDS PostgreSQL (Multi-AZ) with per-service schemas and parameter groups
  - Provision Amazon MSK (Kafka) cluster with topic configurations and DLQ topics
  - Provision Amazon ElastiCache Redis cluster mode with replication groups
  - Provision AWS S3 buckets (documents, photos, invoices, media) with server-side encryption (SSE-KMS)
  - Provision AWS KMS keys for database encryption, S3 encryption, and field-level PII encryption
  - Provision AWS WAF with OWASP Top 10 rule set and attach to API Gateway
  - Provision AWS API Gateway with JWT authorizer, rate limiting rules, and X-Correlation-ID injection
  - Provision AWS Secrets Manager with placeholder secrets for all service credentials
  - Configure Kubernetes namespaces (auth-customer-provider, booking-dispatch-location, payment-invoice-rating, notification-chat-complaint, admin-reporting-promotion, catalog-pricing-verification)
  - **Dependencies:** none
  - **Acceptance:** `terraform apply` completes without error on a clean AWS account; all resources visible in the AWS console; EKS cluster accessible via `kubectl`

- [x] 2. Configure Kubernetes HPA, autoscaling, and observability stack
  - Define Kubernetes HPA manifests targeting 70% CPU for every microservice namespace
  - Add KEDA or custom metrics-based autoscaling rules for Kafka consumer lag on consumer services
  - Deploy Prometheus Operator and configure scrape targets for all service `/metrics` endpoints
  - Deploy Grafana with pre-built dashboards covering request rate, error rate, p50/p95/p99 latency, Kafka consumer lag, DB connection pool, cache hit rate, and booking funnel
  - Configure CloudWatch log groups and structured log forwarding from EKS pods
  - Set up alerting rules: p99 > 2 s or error rate > 1% over any 5-minute window → PagerDuty/SNS
  - **Dependencies:** Task 1
  - **Acceptance:** Prometheus scrapes all placeholder services; Grafana dashboards load; a synthetic alert fires when a test metric threshold is breached

- [x] 3. CI/CD pipeline scaffolding
  - Create GitHub Actions (or equivalent) workflow: lint → unit-test → build Docker image → push to ECR → deploy to staging via Helm
  - Add branch protection requiring passing CI before merge to main
  - Define Helm chart template shared across all microservices (Deployment, Service, HPA, ConfigMap, ServiceAccount)
  - Add staging gate: integration tests must pass before production promotion (Requirement 27.9)
  - Configure Docker build caching and multi-stage builds to keep image sizes minimal
  - **Dependencies:** Task 1
  - **Acceptance:** A sample Spring Boot hello-world service is built, pushed to ECR, and deployed to EKS staging via the pipeline end-to-end without manual steps

- [x] 4. Shared Java libraries — RBAC middleware and JWT validation
  - Create `homefix-shared-security` Maven/Gradle module
  - Implement `JwtValidationFilter` that validates JWT signature and expiry; rejects with 401 on failure
  - Implement `RbacEnforcementFilter` that checks role claims in JWT against a per-endpoint allow-list; returns 403 on insufficient role
  - Implement `X-Correlation-ID` propagation interceptor (read from inbound header or generate UUID v4)
  - Write unit tests covering valid token, expired token, invalid signature, missing role, and correct role
  - **Dependencies:** Task 1
  - **Acceptance:** Filter chain correctly allows/denies requests across all role/endpoint combinations; unit tests pass at ≥ 80% line coverage

- [x] 5. Shared Java libraries — structured logging, OpenTelemetry tracing, and error schema
  - Create `homefix-shared-observability` Maven/Gradle module
  - Configure Logback JSON encoder with mandatory fields: timestamp (ISO 8601), serviceName, traceId, spanId, logLevel, message, and optional entity IDs
  - Integrate OpenTelemetry Java agent auto-instrumentation for HTTP and Kafka spans; add bookingId/userId as span attributes
  - Ensure no PII (name, email, phone, address, national ID, card number) is logged (Requirement 26.4)
  - Define `ErrorResponseDto` with `errorCode`, `message`, `details`, `correlationId` fields; add Jackson serialisation
  - Write unit tests for log field presence and PII scrubbing
  - **Dependencies:** Task 4
  - **Acceptance:** A test service emits structured JSON logs with all mandatory fields; traces appear in the configured tracing backend; PII fields are absent from all log and trace output

- [x] 6. Shared Java libraries — Outbox pattern base and Kafka producer/consumer utilities
  - Create `homefix-shared-outbox` Maven/Gradle module
  - Implement `OutboxEventEntity` JPA entity and repository with `status` (PENDING, PUBLISHED, FAILED) and retry count
  - Implement `OutboxEventPublisher` that writes the outbox row within the same `@Transactional` boundary as the state change
  - Implement `KafkaProducerTemplate` with idempotency config and `acks=all`
  - Implement `IdempotentKafkaConsumer` base class that persists the Kafka `eventId` on first processing and skips duplicates (Requirement 22.5)
  - Implement DLQ forwarder: after 3 consumer retries with 5 s delay, publish to dead-letter topic (Requirement 22.6)
  - Write unit tests for outbox write atomicity, duplicate event skipping, and DLQ forwarding
  - **Dependencies:** Task 5
  - **Acceptance:** A test producer writes an outbox row and a test consumer correctly skips a redelivered event ID; DLQ receives the event after 3 failed consumer retries

---

### Phase 2 — Core Services

- [x] 7. Auth Service — OTP registration and JWT issuance
  - Scaffold Spring Boot microservice `auth-service` using shared libraries (Tasks 4–6)
  - Implement `POST /auth/register/otp`: validate mobile number, send OTP via SMS gateway, store OTP in Redis with 5-minute TTL; reject with error if SMS delivery fails without creating a pending session (Requirement 1.16)
  - Implement `POST /auth/register/verify`: validate OTP, enforce 5-attempt lockout for 30 minutes (Requirement 1.3, Property 25), create USER record, return JWT access token (15-min TTL) and refresh token (30-day TTL)
  - Implement OTP per-phone rate limiting: max 5 OTP requests per phone per hour via API Gateway config (Requirement 23.4)
  - Implement `GET /auth/introspect`: validate JWT signature and return claims for API Gateway use
  - Write unit tests: OTP happy path, expired OTP, incorrect OTP lockout, SMS delivery failure, valid introspect, invalid introspect
  - **Dependencies:** Tasks 4, 5, 6
  - **Acceptance:** Registration flow creates a verified account and returns tokens; fifth consecutive wrong OTP locks the session for exactly 30 minutes; unit tests pass

- [x] 8. Auth Service — refresh token rotation, social login, RBAC, and logout
  - Implement `POST /auth/token/refresh`: issue new access token, rotate refresh token in Redis; detect replay (same token used twice), invalidate entire token family on replay (Requirement 1.9, 1.10, Property 26)
  - Implement `POST /auth/login/social` for Google and Apple: validate provider identity token, create or retrieve account, return JWT + refresh token; return 401 with error code on invalid/expired identity token (Requirement 1.5)
  - Implement multi-role support: a single user account can hold both CUSTOMER and SERVICE_PROVIDER roles simultaneously (Requirement 1.14)
  - Implement `POST /auth/logout`: revoke refresh token in Redis within 1 second (Requirement 1.12)
  - Implement bcrypt password hashing with cost factor ≥ 12 where passwords are used (Requirement 26.5)
  - Implement OAuth2/OIDC compliance (Requirement 23.10)
  - Write unit tests: refresh rotation, replay detection, social login valid/invalid, logout revocation, multi-role JWT claims
  - **Dependencies:** Task 7
  - **Acceptance:** Replay of a refresh token invalidates the token family; social login creates an account on first call and retrieves it on subsequent calls; unit tests pass

- [x] 9. Customer Service — profile management and PII encryption
  - Scaffold Spring Boot microservice `customer-service` using shared libraries
  - Implement `PUT /customers/{id}/profile`: accept display name (1–100 chars), valid email, profile photo (JPEG/PNG ≤ 5 MB); persist all fields with email encrypted via KMS (Requirement 2.1, 26.3)
  - Implement `POST /customers/{id}/addresses`: accept address with GPS coordinates; reverse-geocode via Maps API and store; if geocoding fails store raw coordinates with warning (Requirement 2.2); enforce 10-address maximum (Requirement 2.3)
  - Implement `DELETE /customers/{id}/addresses/{addressId}`: reject if address is used by an active Booking with error (Requirement 2.6); auto-promote most recent address to default when default is deleted (Requirement 2.4)
  - Implement GPS-based location detection endpoint; return error if GPS denied (Requirement 2.5)
  - Ensure all PII fields stored encrypted at rest (Requirement 2.7)
  - Implement `POST /customers/{id}/deletion`: accept data deletion request, acknowledge within 24 hours, schedule anonymization within 30 days (Requirement 26.8, 26.9)
  - Write unit tests: profile update validation, address limit, default address promotion, GPS geocode failure, PII encryption presence
  - **Dependencies:** Tasks 4, 5, 6
  - **Acceptance:** PII fields are AES-256 encrypted in the database; address deletion blocked on active bookings; unit tests pass

- [x] 10. Provider Service — profile, skills, availability, and wallet
  - Scaffold Spring Boot microservice `provider-service` using shared libraries
  - Implement `PUT /providers/{id}/profile`: accept service categories (max 5 active), subcategories (max 10 per category), skill tags (1–20), years of experience (0–50), service radius (1–100 km); reject deactivated categories/subcategories (Requirement 4.1–4.3, 4.8)
  - Implement `PUT /providers/{id}/radius`: update radius within 5 seconds (Requirement 4.4)
  - Implement `PUT /providers/{id}/availability`: persist day/hour slots at 1-hour granularity; reject overlapping slots (Requirement 4.5)
  - Implement `PUT /providers/{id}/emergency-availability`: toggle emergency flag within 5 seconds (Requirement 4.6)
  - Implement wallet balance tracking: maintain balance reflecting cumulative earnings minus settlements and deductions; itemize platform fee and penalty deductions (Requirement 14.1)
  - Implement settlement request `POST /providers/{id}/settlements`: validate amount ≥ 1.00 and ≤ wallet balance, require verified bank account; store bank details encrypted via KMS (Requirement 4.9, 14.2)
  - Implement paginated earnings history endpoint (Requirement 14.5)
  - Auto-flag provider for Admin review when aggregate rating drops below 3.0 (Requirement 4.7)
  - Write unit tests: radius validation, overlapping availability, service radius boundary, wallet credit, settlement validation
  - **Dependencies:** Tasks 4, 5, 6
  - **Acceptance:** Service radius outside 1–100 km is rejected; bank account data is encrypted in DB; unit tests pass

- [x] 11. Verification Service — document upload and state machine
  - Scaffold Spring Boot microservice `verification-service` using shared libraries
  - Implement verification state machine with permitted transitions: PENDING → DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED or REJECTED → BACKGROUND_CHECK_PENDING → BACKGROUND_CHECK_COMPLETED → APPROVED or REJECTED; APPROVED ↔ SUSPENDED (Requirement 5.1)
  - Enforce: any transition not in the permitted map is rejected with current state and disallowed target in the error (Requirement 5.2, Property 24)
  - Implement `POST /verifications/{providerId}/documents`: upload Government ID, address proof, skill certification to S3 with SSE; transition status to DOCUMENT_SUBMITTED (Requirement 5.3)
  - Implement Admin endpoints to mark documents verified, trigger background check, approve, reject, suspend provider; record actor, timestamp, reason in audit trail (Requirement 5.4–5.9, 5.11)
  - Block job assignment for any provider whose status is not APPROVED (Requirement 5.10)
  - On suspension: remove provider from active dispatch pool and cancel pending job offers (Requirement 5.9)
  - On rejection: notify provider via Notification Service (Requirement 5.8)
  - Write unit tests: each valid transition, each invalid transition (rejection + error content), audit trail entry creation
  - **Dependencies:** Tasks 4, 5, 6, 10
  - **Acceptance:** Invalid state transitions return descriptive errors; audit trail forms a contiguous chain; unit tests pass

- [x] 12. Service Catalog Service — categories, subcategories, and Redis cache
  - Scaffold Spring Boot microservice `catalog-service` using shared libraries
  - Implement Admin CRUD for `ServiceCategory`: name, description, icon, display order, active flag (Requirement 3.1–3.2)
  - Implement Admin CRUD for `ServiceSubcategory`: parent category, name, base price (0.01–999,999.99), estimated duration (1–480 min), skill tags (max 20), emergency availability flag (Requirement 3.3, 3.7)
  - Reject subcategory creation under non-existent or deactivated parent (Requirement 3.4)
  - On category deactivation: exclude from customer-facing listings and block new bookings for its subcategories (Requirement 3.5)
  - Reject deletion of category with active providers or active bookings; return count and IDs of blockers (Requirement 3.6)
  - Implement Redis read-through cache with 300-second TTL; cache invalidated on any Admin update (Requirement 3.8)
  - Write unit tests: category activation/deactivation, subcategory under deactivated parent rejection, cache TTL compliance
  - **Dependencies:** Tasks 4, 5, 6
  - **Acceptance:** Catalog responses reflect DB state within ≤ 300 s; deactivated categories absent from customer APIs; unit tests pass

- [x] 13. Pricing Engine — itemized price calculation and Admin configuration
  - Scaffold Spring Boot microservice `pricing-engine` using shared libraries
  - Implement price formula: `total = base_price + distance_charge + time_charge + parts_materials_charge + emergency_charge + weekend_surcharge + night_surcharge + demand_surge_charge + platform_fee + taxes − discount_amount − coupon_amount`; enforce `total ≥ 0.01` (Requirement 6.1, Property 1)
  - Apply emergency multiplier (cap at configured max, default 2.0×) (Requirement 6.2, Property 2)
  - Apply surge multiplier (cap at configured max, default 2.0×) (Requirement 6.3, Property 3)
  - When both multipliers apply: `effective = base × emergency_multiplier × surge_multiplier`, combined cap = sum of both caps (Requirement 6.4, Property 4)
  - Implement distance_charge = distance_km × per_km_rate, capped at max_travel_charge (Requirement 6.7, Property 5)
  - Apply night surcharge for 22:00–06:00 local time; apply weekend surcharge for Saturday/Sunday (Requirement 6.5, 6.6)
  - Return fully itemized `PriceBreakdownDto` with all components non-null; sum of components must equal total (Requirement 6.9, Property 6)
  - Implement coupon validation: active status, date range, min order value, per-user usage limit, total usage limit; return descriptive error on any violation (Requirement 6.10)
  - Implement provider-specific pricing override: reject if outside configured floor/ceiling for the subcategory (Requirement 6.12, Property 7)
  - Implement Admin-configurable parameters cached in Redis (TTL 60 s); apply to new bookings within 60 s of update (Requirement 6.11)
  - Write unit tests: minimum total (0.01 floor), max multiplier caps, combined multiplier ordering, distance cap, night/weekend surcharge toggling, coupon constraint violations, override floor/ceiling rejection
  - **Dependencies:** Tasks 4, 5, 6, 12
  - **Acceptance:** Final total never below 0.01; itemized breakdown sums equal total; Admin config changes reflected within 60 s; unit tests pass

- [x] 14. Booking Service — creation, price estimate, and state machine enforcement
  - Scaffold Spring Boot microservice `booking-service` using shared libraries
  - Implement `POST /bookings` (scheduled): validate subcategory active, lead time ≥ 2 h, horizon ≤ 90 days; request price estimate from Pricing Engine (fail booking if Pricing Engine unavailable, Requirement 7.4); present itemized estimate; on customer confirmation create Booking with CREATED status and unique reference (Requirement 7.1–7.8)
  - Implement `POST /bookings` (emergency): create Booking with emergency flag within 2 s, transition to SEARCHING_PROVIDER within additional 3 s (Requirement 8.1)
  - Accept and store up to 10 media files (JPEG, PNG, MP4, MOV ≤ 50 MB each) in S3 (Requirement 7.2)
  - Enforce booking state machine with all permitted transitions from design (Requirement 9.1); reject invalid transitions with 409 and log attempt (Requirement 9.2, Property 8)
  - Record audit entry per transition: `(bookingId, from_state, to_state, actor_id, actor_role, timestamp, reason)` (Requirement 9.15, Property 9)
  - Publish BookingCreated event to Kafka outbox on transition to SEARCHING_PROVIDER (Requirement 7.5)
  - Implement cancellation logic: no fee for pre-PROVIDER_ON_THE_WAY; configurable fee (0.00–999.99) for later cancellations (Requirement 9.16–9.18)
  - Implement Saga state log: record each Saga step before proceeding (Requirement 24.6); execute compensating transactions in reverse order within 30 s on failure (Requirement 24.7)
  - Write unit tests: all valid transitions, all invalid transitions, emergency timing, media upload limits, price estimate failure handling, cancellation fee logic
  - **Dependencies:** Tasks 4, 5, 6, 9, 12, 13
  - **Acceptance:** Invalid state transitions return 409; audit trail is contiguous; Pricing Engine unavailability returns 503 without creating a booking; unit tests pass

- [x] 15. Booking Service — job execution milestones and parts/materials flow
  - Implement before-photo requirement gate: reject JOB_STARTED transition if no before-photo attached (Requirement 11.2)
  - Implement after-photo requirement gate: reject JOB_COMPLETED transition if no after-photo attached (Requirement 9.10, 11.4)
  - Implement pause/resume: require mandatory reason (1–500 chars) on JOB_PAUSED; track pause intervals (Requirement 11.5)
  - Implement net job duration calculation: sum of JOB_STARTED intervals minus JOB_PAUSED intervals; store with booking record on completion (Requirement 11.6, Property 10)
  - Implement parts/materials flow: record item name, quantity (min 1), unit cost (min 0.01); submit updated parts total to Pricing Engine; transition to ADDITIONAL_QUOTE_REQUIRED → CUSTOMER_APPROVAL_PENDING (Requirement 6.8, 11.3)
  - Implement additional quote approval timeout: auto-complete at original price after 60 minutes of no Customer response (Requirement 9.9)
  - Publish Kafka events: ProviderArriving, ProviderArrived, JobStarted, JobCompleted on each corresponding transition (Requirement 22.1)
  - Write unit tests: before-photo rejection, after-photo rejection, pause reason validation, net duration calculation with multiple pause intervals, parts flow state transitions, approval timeout
  - **Dependencies:** Task 14
  - **Acceptance:** JOB_STARTED blocked without before-photo; net duration correctly sums start/pause intervals; unit tests pass

- [x] 16. Dispatch Engine — matching score, exclusive locking, and radius expansion
  - Scaffold Spring Boot microservice `dispatch-engine` using shared libraries
  - Consume `BookingCreated` event idempotently; query active APPROVED providers within initial radius (default 10 km) with matching skill tags and emergency availability (if emergency booking) (Requirement 8.2)
  - Implement matching score formula: `score = distanceScore×w₁ + availabilityScore×w₂ + ratingScore×w₃ + skillScore×w₄ + performanceScore×w₅` with default weights 0.30/0.25/0.20/0.15/0.10 (Requirement 8.3, Property 18)
  - Use Admin-configured weights if provided; validate that each weight ∈ [0.0, 1.0] and sum = 1.0 exactly (Requirement 8.4, 19.5, Property 19)
  - Acquire exclusive Redis lock `dispatch:lock:provider:{providerId}` (TTL = offer timeout, default 60 s) before sending offer; prevent concurrent offers to the same provider (Requirement 8.11)
  - Send offer to highest-scoring provider; wait up to 60 s for acceptance; on reject/timeout remove from candidate pool and offer to next-highest (Requirement 8.5–8.7)
  - Implement radius expansion: expand by 5 km per cycle, max 3 cycles if no provider accepts (Requirement 8.8)
  - On all cycles exhausted: transition booking to SEARCHING_FAILED, notify customer (push + SMS), alert dispatcher team (Requirement 8.9)
  - On provider acceptance: transition booking to PROVIDER_ACCEPTED within 5 s, publish ProviderAccepted event (Requirement 8.6)
  - Implement bulkhead: separate non-shared thread pool for emergency dispatch (Requirement 24.5)
  - Write unit tests: score formula with custom weights, weight sum validation, exclusive lock acquisition, radius expansion cycle limit, SEARCHING_FAILED on exhaustion
  - **Dependencies:** Tasks 6, 14, 11
  - **Acceptance:** Matching score equals weighted sum of five components; Admin weight update rejected when sum ≠ 1.0; exclusive lock prevents concurrent offers; unit tests pass

- [x] 17. Location Service — WebSocket/SSE ingestion, Redis cache, and ETA
  - Scaffold Spring Boot microservice `location-service` using shared libraries
  - Implement `POST /locations/{bookingId}`: accept Provider GPS update at max rate 1 per 5 s per provider per active booking; store in Redis key `location:{bookingId}:{providerId}`; persist to PostgreSQL for dispute resolution (Requirement 10.1, 10.6)
  - Push coordinates and calculated ETA to all WebSocket/SSE subscribers for that booking (Requirement 10.2, 10.4)
  - Implement tracking view: return last known coordinates within 2 s; if last update > 60 s old, include staleness timestamp (Requirement 10.3, 10.7)
  - Consume `JobStarted` Kafka event idempotently; terminate all active subscriptions and stop accepting updates for that booking (Requirement 10.5)
  - Implement subscription lifecycle management: register/deregister WebSocket sessions per bookingId
  - Write unit tests: rate limiting (> 1/5 s rejected), staleness flag, subscription termination on JobStarted, ETA push
  - **Dependencies:** Tasks 6, 14
  - **Acceptance:** Location updates faster than 1/5 s are rejected; subscriptions terminate on JobStarted event; stale location flag present when last update > 60 s; unit tests pass

- [x] 18. Payment Service — multi-gateway abstraction, idempotency, and transaction state machine
  - Scaffold Spring Boot microservice `payment-service` using shared libraries
  - Implement `PaymentGatewayPort` interface with adapters for Razorpay and Stripe; adding new gateway requires only a new adapter (Requirement 12.1)
  - Support payment methods: UPI, credit/debit card, net banking, in-platform wallet, cash (Requirement 12.2)
  - Implement idempotency: generate key scoped to `(customerId, bookingId)`; store in Redis; duplicate requests return original transaction status without new charge (Requirement 12.3, Property 11)
  - Enforce transaction state machine: PENDING → SUCCESS | FAILED; SUCCESS → REFUNDED | PARTIALLY_REFUNDED; FAILED is terminal (Requirement 12.4, Property 12)
  - Verify cryptographic signature on payment gateway callbacks; reject and log on invalid signature (Requirement 12.5)
  - On SUCCESS: publish PaymentCompleted event (Kafka outbox) and trigger invoice generation within 30 s; retry up to 3× with exponential backoff on failure (Requirement 12.6)
  - Implement refund flow: initiate via gateway, update status within 5 min; on gateway refund failure alert Finance_Admin (Requirement 12.7)
  - Allow up to 3 Customer retry attempts before marking transaction permanently FAILED (Requirement 12.8)
  - Do not store raw card numbers; encrypt stored payment credentials via KMS (Requirement 12.9)
  - On SUCCESS: calculate provider net earnings = payment_amount − platform_fee%; credit to Provider Wallet within 60 s; retry up to 3× on wallet credit failure, then alert Finance_Admin (Requirement 12.10, 12.11, Property 13)
  - Implement settlement bank transfer: initiate within 2 business days; update settlement status (PENDING → PROCESSING → COMPLETED | FAILED); on failure credit back to wallet and notify provider + Finance_Admin (Requirement 14.3, 14.4)
  - Write unit tests: duplicate idempotency key returns original, invalid callback signature rejected, wallet credit on success, state machine invalid transitions rejected, refund flow
  - **Dependencies:** Tasks 6, 14, 10
  - **Acceptance:** Duplicate payment attempts return original status; wallet credited within 60 s of SUCCESS; transaction state machine rejects invalid transitions; unit tests pass

- [x] 19. Invoice Service — PDF generation, S3 storage, and invoice numbering
  - Scaffold Spring Boot microservice `invoice-service` using shared libraries
  - Consume `PaymentCompleted` event idempotently
  - Generate PDF invoice containing: invoice number, date, customer name/address, provider name/verified status, service description, itemized price breakdown with labels, tax amount, discount amount (omit if zero), final total, payment method (Requirement 13.1)
  - Store PDF in S3 with SSE enabled (Requirement 13.2)
  - Deliver signed URL (72-hour expiry) to customer via Notification Service within 60 s of PaymentCompleted (Requirement 13.3)
  - Assign invoice numbers in format `INV-YYYY-MM-NNNNNN` using a per-month atomic sequence; ensure global uniqueness (Requirement 13.4, Property 14)
  - Retain invoices for customer history for minimum 24 months (Requirement 13.5)
  - Generate Provider monthly earnings statements accessible on first day of following month (Requirement 13.6)
  - Implement retry: on PDF generation failure retry up to 3×; alert ops team if all retries fail; do not block booking payment flow (Requirement 13.7)
  - Write unit tests: invoice number format regex, sequential uniqueness within a month, signed URL expiry, PDF retry on generation failure
  - **Dependencies:** Tasks 6, 18
  - **Acceptance:** Invoice numbers match `INV-YYYY-MM-NNNNNN`; no two invoices share a number; signed URL delivered within 60 s; unit tests pass

- [x] 20. Rating & Review Service — submission, weighted aggregate, and fraud detection
  - Scaffold Spring Boot microservice `rating-review-service` using shared libraries
  - Consume `PaymentCompleted` event idempotently; create review prompt for customer (expires in 7 days) and provider (expires in 7 days) (Requirement 15.1, 15.10)
  - Implement `POST /reviews`: validate review window (Requirement 15.2, Property 15); accept integer ratings 1–5 on 5 dimensions, optional text (≤ 1000 chars), up to 5 photo attachments (≤ 10 MB each) (Requirement 15.3)
  - Implement weighted aggregate recalculation: `aggregate = (Σ recent × 1.5 + Σ older × 1.0) / (count_recent × 1.5 + count_older × 1.0)`, rounded to 2 decimal places; recent = ≤ 90 days (Requirement 15.4, Property 16)
  - Implement fraud detection: flag review if (a) ≥ 2 reviews from same IP within 1 h, (b) star rating deviates > 2 SD from provider historical mean, (c) reviewer account created < 24 h before submission; flagged reviews excluded from aggregate until Admin approves (Requirement 15.5, Property 17)
  - Publish `ReviewSubmitted` Kafka event on successful non-flagged review (Requirement 15.6)
  - Auto-flag provider with UNDER_REVIEW status when aggregate drops below 3.0; alert Admin team (Requirement 15.7, 15.8)
  - Implement Admin moderation: remove review, recalculate aggregate, log removal with actor ID and reason in Audit_Log (Requirement 15.9)
  - Write unit tests: expired window rejection, aggregate calculation with mixed recency, fraud flag triggers, flagged review excluded from aggregate, Admin removal recalculates aggregate
  - **Dependencies:** Tasks 6, 18
  - **Acceptance:** Aggregate formula matches weighted calculation; fraud-flagged reviews are excluded from aggregate; expired window submissions are rejected; unit tests pass

- [x] 21. Complaint Service — lifecycle, SLA enforcement, and refund coordination
  - Scaffold Spring Boot microservice `complaint-service` using shared libraries
  - Implement `POST /complaints`: create record with booking reference, category (7 categories), description (≤ 2000 chars), up to 5 attachments (≤ 10 MB each); assign to available support agent (Requirement 16.1)
  - Acknowledge complaint to customer via Notification Service within 30 min of submission (Requirement 16.2)
  - Notify customer of status changes via in-app notification within 5 min (Requirement 16.3)
  - Enforce SLA: 24 h for emergency complaints, 72 h for standard; on breach escalate to Senior_Support_Agent and notify customer (Requirement 16.4)
  - Implement refund approval flow: submit refund request to Payment Service; on Payment Service rejection set REFUND_FAILED, notify customer, alert Finance_Admin (Requirement 16.5, 16.6)
  - Implement dispute hold: on DISPUTED status place hold on provider settlement until complaint closed (Requirement 16.7); release hold on resolution (Requirement 16.8)
  - Aggregate complaint stats (category counts, resolution rates, avg resolution times) refreshed within 60 min for Admin reports (Requirement 16.9)
  - Write unit tests: SLA timer triggering escalation, refund approval and rejection flows, settlement hold placement and release, 30-minute acknowledgment
  - **Dependencies:** Tasks 6, 18, 14
  - **Acceptance:** SLA breach triggers escalation; settlement hold placed on DISPUTED and released on closure; unit tests pass

- [x] 22. Notification Service — multi-channel dispatch, deduplication, and retry
  - Scaffold Spring Boot microservice `notification-service` using shared libraries
  - Implement `SmsPort` and `EmailPort` interfaces with adapters for Twilio/Vonage and SES/SendGrid; swapping vendors requires only a new adapter (Requirement 17.2, 17.3)
  - Consume Kafka events idempotently; dispatch notifications within 10 s of event consumption (Requirement 17.4)
  - Send notifications for all 11 required events: BookingCreated, ProviderAssigned, ProviderAccepted, ProviderRejected, ProviderArriving, ProviderArrived, JobStarted, JobCompleted, PaymentCompleted, BookingCancelled, ReviewSubmitted (Requirement 17.5)
  - Respect user notification preferences; default to all channels enabled when preference data unavailable (Requirement 17.6)
  - Implement deduplication keyed on `(kafkaEventId, channel)`; silently discard already-processed deliveries (Requirement 17.7, Property 22)
  - Implement retry: up to 3 attempts with exponential backoff 1 s → 2 s → 4 s; mark permanently failed after 3 failures (Requirement 17.8)
  - Maintain delivery log with: kafka_event_id, channel, user_id, timestamp, delivery_status, retry_count, error_description (Requirement 17.7)
  - Write unit tests: duplicate event ID discarded, preference-disabled channel skipped, retry schedule, all 11 event triggers
  - **Dependencies:** Tasks 6
  - **Acceptance:** Redelivered Kafka events produce no duplicate notifications; preference-disabled channels are skipped; retry schedule matches 1 s/2 s/4 s; unit tests pass

- [x] 23. Chat Service — WebSocket channels, access control, and message retention
  - Scaffold Spring Boot microservice `chat-service` using shared libraries
  - Consume `ProviderAccepted` Kafka event idempotently; activate a chat channel `(bookingId, customerId, providerId)` (Requirement 18.1)
  - Implement WebSocket message delivery with target latency < 1 s under 500 concurrent channels (Requirement 18.2)
  - Send push notification via Notification Service for offline recipients within 10 s (Requirement 18.3)
  - Persist messages for minimum 90 days from booking creation date regardless of channel status (Requirement 18.4)
  - Consume `PaymentCompleted` and `BookingCancelled` events idempotently; deactivate channel (Requirement 18.5)
  - Reject message sends to deactivated channels with descriptive error (Requirement 18.6)
  - Enforce access control: permit only the customer and provider linked to the booking; return 403 for all others (Requirement 18.7, Property 23)
  - Mask phone numbers; never expose personal mobile numbers in chat (Requirement 18.8)
  - Write unit tests: non-participant receives 403, deactivated channel message rejection, 90-day retention check, channel activation/deactivation lifecycle
  - **Dependencies:** Tasks 6, 14, 22
  - **Acceptance:** Non-participant access returns 403; messages rejected on deactivated channels; unit tests pass

- [x] 24. Admin Service — dashboard, operational modules, and Audit_Log
  - Scaffold Spring Boot microservice `admin-service` using shared libraries
  - Implement dashboard endpoint returning 7 metrics refreshed every 60 s: active bookings, active providers online, 24-h registrations, 24-h gross revenue, avg provider response time, open complaint count, platform rating (Requirement 19.1)
  - Implement all 15 operational module endpoints: User Management, Provider Management, Verification Queue, Service Category Management, Pricing Configuration, Dispatch Rule Configuration, Booking Management, Payment and Refund Management, Complaint Management, Review Moderation, Coupon Management, Notification Templates, Report Generation, Audit Logs, System Configuration (Requirement 19.2)
  - Verification Queue: list DOCUMENT_SUBMITTED providers sorted oldest-first with inline document viewing (Requirement 19.3)
  - Enforce RBAC: ADMIN role can access all modules except System Configuration; SUPER_ADMIN can access all; return 403 for ADMIN on System Configuration (Requirement 19.6, 19.7)
  - Log every Admin action (create/update/delete/approve/reject) to Audit_Log with before/after values (Requirement 19.8)
  - Dispatch weight update endpoint: validate each weight ∈ [0.0, 1.0], sum = 1.0; reject with error on violation, leave existing weights unchanged (Requirement 19.5, Property 19)
  - Write unit tests: ADMIN role blocked from System Configuration, Audit_Log entry per Admin action, dispatch weight validation
  - **Dependencies:** Tasks 4, 5, 6, 11, 12, 13, 16, 18, 19, 20, 21
  - **Acceptance:** ADMIN role receives 403 on System Configuration; every Admin action produces an Audit_Log entry; unit tests pass

- [x] 25. Reporting Service — pre-built reports and async generation
  - Scaffold Spring Boot microservice `reporting-service` using shared libraries
  - Implement 6 pre-built reports: Daily/Weekly/Monthly Revenue Summary, Provider Performance, Service Category Demand, Customer Retention, Complaint Resolution, Payment Reconciliation (Requirement 20.1)
  - Synchronous path (≤ 7-day range): return report within 10 s; return empty report with message if no data (Requirement 20.2)
  - Asynchronous path (> 7-day range): generate in background; notify requestor via email with 7-day expiring download link (Requirement 20.3)
  - Support filters: date range, Service_Category, geographic region, provider; export in PDF and CSV (Requirement 20.4)
  - Connect to analytics data store (Redshift / Aurora); ensure 2-year raw event retention (Requirement 20.5)
  - Restrict Payment Reconciliation and Settlement Reports to Finance_Admin role only; return 403 otherwise (Requirement 20.6)
  - Write unit tests: Finance_Admin restriction, empty result message, synchronous vs asynchronous branching at 7-day threshold
  - **Dependencies:** Tasks 4, 5, 6, 24
  - **Acceptance:** Non-Finance_Admin receives 403 on restricted reports; reports over 7 days trigger async path; unit tests pass

- [x] 26. Promotion/Coupon Service — CRUD, atomic redemption, and cancellation decrement
  - Scaffold Spring Boot microservice `promotion-service` using shared libraries
  - Implement Admin coupon CRUD: create with code (case-insensitive, 4–20 alphanumeric), discount type (FLAT/PERCENTAGE), discount value > 0, min order value ≥ 0, max discount cap (required for PERCENTAGE), valid_from, expiry (after valid_from), per-user limit ≥ 1, total limit ≥ 1 (Requirement 21.1)
  - Implement coupon validation endpoint: check active status, date range, min order value, per-user usage limit, total usage limit; return descriptive error on violated constraint (Requirement 21.2)
  - Implement atomic redemption: increment total_used and per-user usage counters atomically (Redis Lua script or DB serializable transaction) to prevent over-redemption under concurrency (Requirement 21.3, Property 20)
  - Implement Admin deactivation: immediately prevent further redemptions; honour in-flight redemptions past validation (Requirement 21.4)
  - Implement cancellation decrement: atomically decrement both counters when booking using coupon is cancelled before payment captured (Requirement 21.5, Property 21)
  - Write unit tests: coupon creation validation, constraint violation messages, concurrent redemption simulation, deactivation blocking, cancellation decrement
  - **Dependencies:** Tasks 4, 5, 6
  - **Acceptance:** Concurrent redemption requests never exceed configured limits; cancellation decrements restore counters atomically; unit tests pass

- [x] 27. Outbox Processor — poll, publish, and mark published
  - Scaffold standalone Spring Boot application `outbox-processor` using shared outbox libraries
  - Poll `outbox_event` table for PENDING rows using configurable batch size and poll interval
  - Publish each event to the configured Kafka topic; on broker ACK mark row as PUBLISHED within the same DB transaction
  - Implement exponential backoff retry on publish failure: start at 1 s, double each attempt up to 60 s max interval, max 10 retry attempts (Requirement 22.4)
  - After max retries exceeded: emit alert with event ID, Kafka topic, and total attempt count; mark row as FAILED
  - Ensure idempotent publish: if Kafka already contains the event (duplicate detection via idempotent producer config), still mark row PUBLISHED
  - Write unit tests: successful publish marks row PUBLISHED, retry schedule, alert after max retries, idempotent producer config
  - **Dependencies:** Task 6
  - **Acceptance:** Outbox rows are marked PUBLISHED after broker ACK; exponential backoff matches 1 s/2 s/4 s/.../60 s schedule; unit tests pass

- [x] 28. API Gateway — rate limiting, WAF, HTTPS enforcement, and X-Correlation-ID
  - Configure per-user rate limiting: CUSTOMER 100 req/min, PROVIDER 60 req/min; return 429 with Retry-After header on breach (Requirement 23.3, 23.5, Property 27)
  - Configure per-phone OTP rate limiting: max 5 req/phone/hour (Requirement 23.4)
  - Configure WAF rules: block OWASP Top 10 patterns (SQLi, XSS); return 400 with generic error body without internal detail (Requirement 23.6)
  - Enforce HTTPS only; redirect HTTP to HTTPS (301); reject TLS < 1.2 (Requirement 23.7, 23.8)
  - Attach X-Correlation-ID to every routed request: use existing header value if present, otherwise generate UUID v4 (Requirement 23.9)
  - Configure JWT authorizer to call `/auth/introspect`; reject unauthenticated requests with 401 (Requirement 23.1)
  - Write integration tests: unauthenticated request → 401; CUSTOMER at 101 req/min → 429 with Retry-After; HTTP request → 301
  - **Dependencies:** Tasks 1, 7, 8
  - **Acceptance:** All security controls verified via integration tests; rate limit returns 429 + Retry-After; WAF blocks SQLi test payloads; unit tests pass

- [x] 29. Resilience patterns — circuit breakers, retries, and timeouts across all services
  - Add Resilience4j dependency to every microservice via shared parent POM
  - Configure circuit breaker on all synchronous inter-service and external calls: 50% failure rate / 10-call sliding window / 30 s open wait (Requirement 24.1)
  - Configure retry with exponential backoff: max 3 attempts, initial 500 ms, max 8 s, on connection timeout / read timeout / HTTP 5xx (Requirement 24.2)
  - Configure timeouts: ≤ 5 s for critical-path calls (booking, dispatch); ≤ 15 s for all others (Requirement 24.3)
  - On circuit breaker open: return fallback degraded response and emit WARN-level log with dependency name (Requirement 24.4)
  - Verify dispatch engine emergency bulkhead is configured with separate thread pool (Requirement 24.5)
  - Write integration tests: inject downstream failures to trigger circuit breaker open state; verify fallback response and WARN log
  - **Dependencies:** Tasks 7–27
  - **Acceptance:** Circuit breaker transitions to OPEN after 50% failures in 10-call window; fallback response returned to caller; WARN log emitted with dependency name; integration tests pass

---

### Phase 3 — Frontend Applications

- [x] 30. Customer App — project scaffold, routing, and shared infrastructure
  - Scaffold React + TypeScript + Vite project with TanStack Query, Zustand, React Router, Material UI (or equivalent), React Hook Form + Zod (Requirement 28.1)
  - Configure Vite proxy for local API development; set up path aliases
  - Create shared API client (Axios or Fetch) with JWT Bearer token injection and X-Correlation-ID header
  - Create Zustand stores: auth store (JWT, user profile, roles), booking store (active booking state)
  - Set up TanStack Query client with default stale time, retry, and error boundary integration
  - Configure ESLint + Prettier + TypeScript strict mode; add to CI pipeline
  - **Dependencies:** Tasks 7, 8
  - **Acceptance:** `vite build` completes without errors; ESLint passes; dev server proxies to Auth Service

- [x] 31. Customer App — authentication screens (Splash, Login/OTP)
  - Implement Splash screen with HomeFix branding and navigation to Login
  - Implement Login/OTP screen: mobile number input, OTP request, 5-digit OTP input with countdown timer (5 min expiry), error display for lockout (Requirement 1.1–1.3)
  - Implement social login buttons for Google and Apple (Requirement 1.5)
  - Integrate TanStack Query mutations for OTP request and OTP verification
  - Handle token storage in memory (access token) and secure storage (refresh token)
  - **Dependencies:** Task 30
  - **Acceptance:** OTP flow creates a session and navigates to Home; lockout error displays remaining lockout time; Lighthouse mobile score ≥ 80

- [x] 32. Customer App — Home, service category/subcategory selection, and service request
  - Implement Home screen with service category cards fetched from Service Catalog
  - Implement Subcategory selection screen with breadcrumb navigation
  - Implement Service Request screen: address input (GPS detect or manual entry), date/time picker (lead time ≥ 2 h, max 90 days), description field, media upload (up to 10 files, JPEG/PNG/MP4/MOV ≤ 50 MB each), emergency toggle
  - Implement Price Estimate breakdown screen: display all itemized components, coupon code input and validation feedback, confirm/cancel actions
  - Integrate TanStack Query for catalog fetch (cache 5 min) and booking creation mutation
  - **Dependencies:** Tasks 30, 12, 13, 14
  - **Acceptance:** Itemized price estimate displayed before confirmation; emergency toggle triggers correct booking type; media upload validates file types and sizes

- [x] 33. Customer App — Available Professionals list, Live Tracking map, and Service History
  - Implement Available Professionals list: each card shows VERIFIED badge, display name, aggregate rating, jobs count, distance (km), ETA (min), starting price (Requirement 28.6)
  - Implement Live Tracking map: real-time provider location via WebSocket/SSE, ETA countdown, provider info card; handle stale location (> 60 s) with visual indicator
  - Implement Service History screen: paginated booking list with status, date, amount; tap to view booking detail with invoice download (signed URL)
  - Add in-app chat button on active booking screens navigating to Chat screen (WebSocket)
  - Integrate TanStack Query with WebSocket subscription for live tracking
  - **Dependencies:** Tasks 30, 17, 23, 19
  - **Acceptance:** Live tracking updates reflect provider location within 5 s; stale location shows timestamp indicator; invoice download opens PDF

- [x] 34. Provider App — scaffold, authentication, and dashboard
  - Scaffold Provider App using same React + TypeScript + Vite stack as Customer App with mobile-first layout (Requirement 28.2)
  - Implement Login/OTP screen (same OTP flow as Customer App)
  - Implement Dashboard screen: earnings summary (wallet balance, today's earnings), active job list with status indicators
  - Implement Verification status screen showing current state and required next steps
  - **Dependencies:** Tasks 30, 7, 8, 10, 11
  - **Acceptance:** Provider can log in, view wallet balance, and see active jobs; mobile-first layout passes responsive design check at 375px viewport

- [x] 35. Provider App — job request, active job, and completion screens
  - Implement Job Request screen: service details, customer info, accept/decline buttons with 60-second countdown (Requirement 28.8)
  - Implement Job Details screen: customer display name, service address, description, media references, navigation deep-link to coordinates (Requirement 11.1)
  - Implement Active Job screen: before-photo upload (required before JOB_STARTED), pause/resume with mandatory reason, parts/materials entry (item name, quantity, unit cost), after-photo upload (required before JOB_COMPLETED)
  - Implement Job Completion screen: summary of duration, parts, final price; trigger completion
  - Implement Earnings and Settlement History screen: paginated job history with gross/fee/net per job; settlement request form
  - **Dependencies:** Tasks 34, 15, 18
  - **Acceptance:** Before-photo required to start job; after-photo required to complete job; settlement request validates amount and bank account presence

- [x] 36. Admin Portal — scaffold, dashboard, and core operational modules
  - Scaffold Admin Portal using same React + TypeScript + Vite stack with desktop-first layout (Requirement 28.3)
  - Implement dashboard with 7 live-refresh metrics (60 s polling)
  - Implement User Management and Provider Management modules
  - Implement Verification Queue with inline document viewing sorted oldest-first
  - Implement Service Category Management and Pricing Configuration modules
  - Implement Dispatch Rule Configuration with weight validation UI
  - Implement Booking Management, Payment and Refund Management, Complaint Management, Review Moderation modules
  - Implement Coupon Management, Report Generation, Audit Logs, and System Configuration modules (SUPER_ADMIN only)
  - Enforce RBAC in the UI: hide/disable System Configuration for ADMIN role
  - **Dependencies:** Tasks 11, 12, 13, 16, 17, 18, 19, 20, 21, 24, 25, 26
  - **Acceptance:** ADMIN role cannot access System Configuration; dashboard metrics refresh every 60 s; inline document viewer opens PDFs without separate download

---

### Phase 4 — Testing

- [x] 37. Unit tests — Auth Service (all coverage targets)
  - Write unit tests for OTP lockout: verify exactly 30-minute lockout after 5 consecutive wrong OTPs; verify no attempt succeeds during lockout window
  - Write unit tests for OTP expiry: verify invalidation after 5 minutes
  - Write unit tests for refresh token rotation: new token issued, old token invalidated
  - Write unit tests for refresh token replay: entire token family invalidated on second use of same token
  - Write unit tests for social login: valid identity token creates/retrieves account; invalid token returns 401 with error code
  - Write unit tests for multi-role JWT claims: single user with both CUSTOMER and SERVICE_PROVIDER roles
  - Verify ≥ 80% line coverage on Auth Service
  - **Dependencies:** Tasks 7, 8
  - **Acceptance:** All unit tests pass; JaCoCo reports ≥ 80% line coverage on Auth Service

- [x] 38. Unit tests — Pricing Engine and Booking Service (all coverage targets)
  - Write unit tests for pricing formula boundary values: zero coupon, maximum emergency multiplier, maximum surge multiplier, combined multiplier ordering, minimum total = 0.01
  - Write unit tests for distance charge cap enforcement
  - Write unit tests for night surcharge (22:00–06:00) and weekend surcharge (Sat/Sun) toggling
  - Write unit tests for all valid booking state machine transitions
  - Write unit tests for all invalid booking state machine transitions (verify 409 + audit entry)
  - Write unit tests for job duration net calculation with multiple pause intervals
  - Write unit tests for cancellation fee logic (pre/post PROVIDER_ON_THE_WAY states)
  - Verify ≥ 80% line coverage on Pricing Engine and Booking Service
  - **Dependencies:** Tasks 13, 14, 15
  - **Acceptance:** All unit tests pass; JaCoCo reports ≥ 80% line coverage on both services

- [x] 39. Unit tests — remaining services (Payment, Invoice, Rating, Dispatch, Notification, Chat, Verification, Complaint, Promotion)
  - Payment Service: idempotency key duplicate returns original, invalid callback signature rejected, wallet credit on SUCCESS, refund flow
  - Invoice Service: invoice number format and uniqueness, signed URL expiry, PDF retry
  - Rating & Review Service: weighted aggregate formula, fraud flag triggers, Admin removal recalculates aggregate, expired window rejection
  - Dispatch Engine: score formula, weight sum validation, exclusive lock, SEARCHING_FAILED on exhaustion
  - Notification Service: duplicate event ID discarded, preference-disabled channel skipped, retry schedule
  - Chat Service: non-participant 403, deactivated channel rejection, 90-day retention
  - Verification Service: all valid transitions, all invalid transitions, audit trail completeness
  - Complaint Service: SLA timer, settlement hold/release, refund coordination
  - Promotion Service: atomic redemption, cancellation decrement, deactivation
  - Verify ≥ 80% line coverage on each listed service
  - **Dependencies:** Tasks 11, 18, 19, 20, 16, 21, 22, 23, 26
  - **Acceptance:** All unit tests pass; JaCoCo reports ≥ 80% line coverage per service

- [x] 40. Property-based tests — Pricing Engine properties (Properties 1–7)
  - Implement jqwik PBT: `@Property(tries=100) @Label("Feature: homefix-platform, Property 1: Pricing formula non-negativity")` — for all valid input combinations, `total ≥ 0.01`
  - Implement PBT Property 2: for any emergency booking, emergency multiplier factor ≤ configured cap (default 2.0×)
  - Implement PBT Property 3: for any surge booking, surge multiplier ≤ configured cap (default 2.0×)
  - Implement PBT Property 4: for combined emergency + surge, effective multiplier ≤ sum of both caps; emergency applied first
  - Implement PBT Property 5: for any provider–customer distance and per-km rate, distance_charge ≤ max_travel_charge
  - Implement PBT Property 6: for any booking, all itemized components non-null, and sum of components = returned total
  - Implement PBT Property 7: provider-specific override rejected iff price < floor or price > ceiling; accepted otherwise
  - **Dependencies:** Task 13
  - **Acceptance:** All 7 property tests pass with ≥ 100 tries each; no counterexample found

- [x] 41. Property-based tests — Booking and Payment properties (Properties 8–13)
  - Implement PBT Property 8: for any booking state and any target state, transition permitted iff target is in the permitted map for source; otherwise 409 returned
  - Implement PBT Property 9: for any successfully applied state transition, exactly one audit entry exists with all required fields; audit entries form a contiguous chain from CREATED
  - Implement PBT Property 10: for any sequence of JOB_STARTED / JOB_PAUSED intervals, net duration = Σ started intervals − Σ paused intervals
  - Implement PBT Property 11: for any duplicate (customerId, bookingId) idempotency key, Payment Service returns original transaction status and creates no new record
  - Implement PBT Property 12: for any payment transaction state, transition permitted iff in defined map; others rejected
  - Implement PBT Property 13: for any booking whose payment transitions to SUCCESS, provider wallet balance increases by exactly (payment_amount − platform_fee) within 60 s
  - **Dependencies:** Tasks 14, 15, 18
  - **Acceptance:** All 6 property tests pass with ≥ 100 tries each; no counterexample found

- [x] 42. Property-based tests — Invoice, Reviews, Dispatch properties (Properties 14–19)
  - Implement PBT Property 14: for any set of generated invoices, no two share an invoice number; every number matches `INV-YYYY-MM-NNNNNN` with monotonically increasing sequence per month
  - Implement PBT Property 15: review submission accepted iff current timestamp < PAYMENT_COMPLETED + 7 days; rejected iff expired
  - Implement PBT Property 16: for any provider with mixed recency reviews, aggregate = weighted formula (1.5× recent, 1.0× older), rounded to 2 decimal places
  - Implement PBT Property 17: for any flagged-but-not-approved review, it is excluded from the aggregate calculation
  - Implement PBT Property 18: for any eligible provider set and any valid weight set (sum = 1.0), matching score = exact weighted sum of five components
  - Implement PBT Property 19: Admin dispatch weight update accepted iff each weight ∈ [0.0, 1.0] and sum = 1.0 exactly; rejected otherwise; existing weights unchanged on rejection
  - **Dependencies:** Tasks 16, 19, 20, 24
  - **Acceptance:** All 6 property tests pass with ≥ 100 tries each; no counterexample found

- [x] 43. Property-based tests — Coupon, Notification, Chat, Verification, Auth properties (Properties 20–27)
  - Implement PBT Property 20: for any concurrently arriving coupon redemptions, total_used and per-user counters never exceed configured limits
  - Implement PBT Property 21: for any booking cancelled before payment with a coupon, both counters decremented atomically to pre-redemption values
  - Implement PBT Property 22: for any Kafka event redelivered to Notification Service, no duplicate notification dispatched to that user on that channel
  - Implement PBT Property 23: for any message operation on a chat channel, permitted iff requesting user is the customer or provider linked to the booking; 403 otherwise
  - Implement PBT Property 24: for any verification status transition request, permitted iff target is in the defined map; rejected with error identifying current state and disallowed target otherwise
  - Implement PBT Property 25: for any OTP session with ≥ 5 consecutive wrong submissions, session locked for exactly 30 minutes; no attempt succeeds during lockout
  - Implement PBT Property 26: for any refresh token used more than once, entire token family invalidated; no token in the family can produce a new access token
  - Implement PBT Property 27: for any CUSTOMER-role user, requests up to rate limit (100/min) succeed; all subsequent requests in the same window receive 429 with Retry-After header
  - **Dependencies:** Tasks 7, 8, 22, 23, 26, 11
  - **Acceptance:** All 8 property tests pass with ≥ 100 tries each; no counterexample found

- [x] 44. Integration tests — end-to-end booking flows (scheduled and emergency)
  - Stand up staging environment with all 19 microservices and dependencies
  - Test scheduled booking flow: register customer → create booking → price estimate → confirm → dispatch → provider accept → provider on way → arrived → job started (with before-photo) → job completed (with after-photo) → customer confirm → payment → invoice received
  - Test emergency booking flow: create emergency booking → dispatch within 5 s → provider assigned → end-to-end to payment
  - Test searching-failed path: exhaust all dispatch candidates across 3 radius expansions → verify SEARCHING_FAILED and customer notification
  - Test additional quote flow: parts/materials added mid-job → customer approval → job resumed → completed at updated price
  - Test cancellation with fee: cancel in PROVIDER_ON_THE_WAY state → verify fee applied → partial refund initiated
  - **Dependencies:** Tasks 14, 15, 16, 17, 18, 22
  - **Acceptance:** All happy paths complete end-to-end without manual intervention; SEARCHING_FAILED path triggers customer notification; cancellation fee applied correctly on staging

- [x] 45. Integration tests — Kafka, Outbox, circuit breakers, and verification flow
  - Test Outbox Processor: inject DB commit followed by Kafka broker failure → verify exponential backoff retries → broker recovers → event published → outbox row marked PUBLISHED
  - Test idempotent Kafka consumption: redeliver the same event to Notification Service → verify only one notification dispatched
  - Test DLQ: force consumer to fail 3 times on one event → verify event lands in dead-letter topic and processing continues
  - Test circuit breaker: inject 50% failure rate on downstream dependency → verify circuit opens after 10-call window → fallback response returned → WARN log emitted
  - Test Provider verification flow end-to-end: document upload → Admin marks verified → background check initiated → completed → Admin approves → provider appears in dispatch candidate pool
  - **Dependencies:** Tasks 6, 27, 29, 11
  - **Acceptance:** Outbox retry schedule matches spec; DLQ receives event after 3 failures; circuit breaker opens at 50% failure rate; verification flow produces APPROVED provider visible to dispatch

- [x] 46. Contract tests (Pact) — inter-service API contracts
  - Write Pact consumer contracts for Booking Service consuming Pricing Engine API
  - Write Pact consumer contracts for Booking Service consuming Notification Service API
  - Write Pact consumer contracts for Booking Service consuming Dispatch Engine API
  - Write Pact provider verifications in Pricing Engine, Notification Service, and Dispatch Engine
  - Publish contracts to Pact Broker; add can-i-deploy check to CI pipeline before staging deployment
  - **Dependencies:** Tasks 13, 14, 16, 22
  - **Acceptance:** All consumer contracts verified by providers; can-i-deploy passes; incompatible schema change causes contract test failure and blocks deployment

## Task Dependency Graph

```json
{
  "waves": [
    {
      "wave": 1,
      "tasks": ["1"],
      "description": "AWS core infrastructure — all other tasks depend on this"
    },
    {
      "wave": 2,
      "tasks": ["2", "3", "4"],
      "description": "HPA/observability, CI/CD, and shared security library — can run in parallel after Task 1"
    },
    {
      "wave": 3,
      "tasks": ["5"],
      "description": "Shared observability library — depends on Task 4"
    },
    {
      "wave": 4,
      "tasks": ["6"],
      "description": "Shared Outbox/Kafka library — depends on Task 5"
    },
    {
      "wave": 5,
      "tasks": ["7", "9", "10", "12", "22", "26", "27"],
      "description": "First-wave services with only shared-library dependencies — can run in parallel after Task 6"
    },
    {
      "wave": 6,
      "tasks": ["8", "11", "13"],
      "description": "Auth rotation/social, Verification Service, Pricing Engine — depend on wave-5 services"
    },
    {
      "wave": 7,
      "tasks": ["14", "16", "17", "18", "23", "24", "28"],
      "description": "Booking creation, Dispatch, Location, Payment, Chat, Admin, API Gateway — depend on wave-6 services"
    },
    {
      "wave": 8,
      "tasks": ["15", "19", "20", "21", "25", "29"],
      "description": "Job execution, Invoice, Rating, Complaint, Reporting, Resilience patterns — depend on wave-7 services"
    },
    {
      "wave": 9,
      "tasks": ["30"],
      "description": "Customer App scaffold — depends on Auth Service (Tasks 7, 8)"
    },
    {
      "wave": 10,
      "tasks": ["31", "32", "34", "36"],
      "description": "Customer auth screens, Customer home/request, Provider App scaffold, Admin Portal — parallel after wave-9"
    },
    {
      "wave": 11,
      "tasks": ["33", "35"],
      "description": "Customer tracking/history, Provider job screens — depend on wave-10 frontend scaffolds and backend services"
    },
    {
      "wave": 12,
      "tasks": ["37", "38", "39", "40", "41", "42", "43"],
      "description": "All unit and property-based test tasks — can run in parallel as their respective services complete"
    },
    {
      "wave": 13,
      "tasks": ["44", "45", "46"],
      "description": "Integration and contract tests — depend on all Phase 2 services being deployed to staging"
    }
  ]
}
```

## Notes

- All Spring Boot microservices inherit from a shared parent POM that includes `homefix-shared-security`, `homefix-shared-observability`, and `homefix-shared-outbox` modules.
- Property-based tests use **jqwik** and must be tagged with `@Label("Feature: homefix-platform, Property N: {property_text}")` and run with `@Property(tries = 100)` minimum.
- No PII (full name, email, phone, national ID, card number, precise address) may appear in logs or distributed traces (Requirement 26.4). The shared observability library enforces this via a log filter.
- All secrets must be stored in AWS Secrets Manager; no secret values in source code, container images, environment variable files, or Kubernetes ConfigMaps (Requirement 27.8).
- Each microservice must expose `/health/liveness` and `/health/readiness` endpoints and a `/metrics` Prometheus endpoint (Requirement 25.3, 25.4).
- The Audit_Log table is append-only with write access restricted to service accounts only (Requirement 26.10).
- Frontend applications must meet WCAG 2.1 AA; full validation requires manual testing with assistive technologies in addition to automated checks (Requirement 28.5).
