# Requirements Document

## Introduction

HomeFix is a production-ready, scalable, secure on-demand home services marketplace with the tagline "Verified Help. When You Need It." The platform connects homeowners with verified, background-checked service professionals for emergency and scheduled home services across categories including plumbing, electrical, AC repair, carpentry, appliance repair, cleaning, painting, pest control, and locksmith services.

The platform serves multiple user roles — customers, service providers, dispatchers, administrators, support agents, and finance administrators — and is delivered as three frontend applications (customer app, provider app, admin portal) backed by 19 microservices on a cloud-native AWS infrastructure.

HomeFix addresses the core problem of homeowners being unable to find trustworthy professionals quickly, especially during emergencies, with opaque pricing and unpredictable service quality.

---

## Glossary

- **Customer**: A registered homeowner or tenant who books home services through the HomeFix platform.
- **Provider**: A registered, verified, background-checked service professional who fulfills home service jobs.
- **Dispatcher**: An operations staff member who monitors and intervenes in the job assignment process.
- **Admin**: A platform administrator responsible for platform configuration, user management, and oversight.
- **Super_Admin**: The highest-privilege administrator with full platform access including system configuration.
- **Support_Agent**: A customer support staff member who handles complaints, disputes, and user issues.
- **Finance_Admin**: A finance team member responsible for payment reconciliation, refunds, and financial reports.
- **Booking**: A service request created by a Customer, progressing through a defined state machine lifecycle.
- **Job**: The active work order assigned to a Provider, corresponding to a Booking.
- **Emergency_Service**: A Booking flagged as urgent requiring immediate Provider dispatch.
- **Scheduled_Service**: A Booking for a future date and time selected by the Customer.
- **Service_Category**: A top-level grouping of related home services (e.g., Plumbing, Electrical).
- **Service_Subcategory**: A specific service type within a Service_Category (e.g., Pipe Leakage within Plumbing).
- **Matching_Engine**: The system component that scores and ranks available Providers for a given Booking.
- **Pricing_Engine**: The system component that calculates transparent, itemized pricing for a Booking.
- **Dispatch_Engine**: The system component that selects and assigns a Provider to a Booking.
- **Verification_Workflow**: The multi-step background check and document review process for Provider approval.
- **OTP**: One-time password used for authentication.
- **RBAC**: Role-based access control governing what actions each user role may perform.
- **ETA**: Estimated time of arrival of the Provider at the Customer's location, expressed in minutes.
- **Matching_Score**: A weighted composite score used by the Dispatch_Engine to rank Providers.
- **Emergency_Multiplier**: A pricing factor applied to base price during Emergency_Service requests.
- **Surge_Multiplier**: A demand-based pricing factor applied when Provider supply is low relative to demand.
- **Platform_Fee**: The commission percentage retained by HomeFix from each completed Booking payment.
- **Outbox**: The transactional outbox table used to ensure reliable Kafka event publishing.
- **Saga**: A distributed transaction pattern used to maintain consistency across microservices.
- **Idempotency_Key**: A unique token ensuring a payment or operation is processed exactly once.
- **Wallet**: A Provider's in-platform earnings balance that can be withdrawn to their bank account.
- **Settlement**: The transfer of earned funds from the Platform to a Provider's bank account.
- **Audit_Log**: An immutable record of significant system events for compliance and forensics.
- **WebSocket**: A full-duplex communication protocol used for real-time updates.
- **SSE**: Server-Sent Events, a unidirectional protocol used for real-time streaming updates.
- **OpenTelemetry**: A vendor-neutral observability framework for distributed tracing and metrics.
- **Circuit_Breaker**: A resilience pattern that prevents repeated calls to a failing downstream service.
- **Resilience4j**: The Java resilience library implementing timeouts, retries, circuit breakers, and bulkheads.
- **API_Gateway**: The single entry point for all client requests, handling routing, auth, and rate limiting.
- **KMS**: AWS Key Management Service used for encryption key management.
- **WAF**: AWS Web Application Firewall used to block malicious HTTP traffic.
- **EKS**: Amazon Elastic Kubernetes Service used to orchestrate microservice containers.
- **MSK**: Amazon Managed Streaming for Apache Kafka used for event streaming.
- **RDS**: Amazon Relational Database Service hosting PostgreSQL instances.
- **ElastiCache**: Amazon ElastiCache hosting Redis clusters.

---

## Requirements

### Requirement 1: User Registration and Authentication

**User Story:** As a new user, I want to register and authenticate securely, so that I can access the HomeFix platform with confidence that my account is protected.

#### Acceptance Criteria

1. WHEN a new Customer submits a registration request with a valid mobile number, THE Auth_Service SHALL send an OTP to that mobile number within 10 seconds.
2. WHEN a Customer submits a correct OTP within 5 minutes of issuance, THE Auth_Service SHALL create a verified Customer account and return a JWT access token and refresh token.
3. IF a Customer submits an incorrect OTP 5 or more times consecutively, THEN THE Auth_Service SHALL lock the OTP session for 30 minutes and return an error response indicating the lockout duration.
4. IF an OTP is not submitted within 5 minutes of issuance, THEN THE Auth_Service SHALL invalidate the OTP and require the Customer to request a new one.
5. WHEN a Customer authenticates via a supported social login provider (Google, Apple), THE Auth_Service SHALL validate the provider's identity token and, if valid, create or retrieve the Customer account and return a JWT access token and refresh token; IF the identity token is invalid or expired, THEN THE Auth_Service SHALL return a 401 Unauthorized response with an error code identifying the failure reason.
6. WHEN a Provider submits a registration request with a valid mobile number, THE Auth_Service SHALL send an OTP to that mobile number within 10 seconds.
7. WHEN a Provider submits a correct OTP within 5 minutes of issuance, THE Auth_Service SHALL create a Provider account with status PENDING and return a JWT access token.
8. THE Auth_Service SHALL issue JWT access tokens with a maximum validity of 15 minutes and refresh tokens with a maximum validity of 30 days.
9. WHEN a client submits a valid refresh token, THE Auth_Service SHALL issue a new access token and rotate the refresh token.
10. WHEN a refresh token is used more than once (replay attempt), THE Auth_Service SHALL invalidate the entire token family for that user and require re-authentication.
11. THE Auth_Service SHALL enforce RBAC such that each API endpoint is accessible only to users holding a permitted role; IF a request is made to an endpoint by a user without the required role, THEN THE Auth_Service SHALL return a 403 Forbidden response indicating insufficient permissions.
12. WHEN an authenticated user requests logout, THE Auth_Service SHALL revoke the refresh token and invalidate the active session within 1 second.
13. IF a JWT is submitted with an expired or invalid signature, THEN THE API_Gateway SHALL reject the request with a 401 Unauthorized response.
14. THE Auth_Service SHALL support multiple roles per user account such that a single user may simultaneously hold both CUSTOMER and SERVICE_PROVIDER roles.
15. IF a client submits a refresh token that is already expired or has been explicitly revoked (non-replay scenario), THEN THE Auth_Service SHALL return a 401 Unauthorized response and require the user to re-authenticate with credentials.
16. IF the OTP SMS delivery fails due to an undeliverable or invalid mobile number, THEN THE Auth_Service SHALL return an error response identifying the delivery failure and SHALL NOT create a pending OTP session for that number.

---

### Requirement 2: Customer Profile and Address Management

**User Story:** As a Customer, I want to manage my profile and multiple saved addresses with GPS detection, so that I can book services efficiently from any of my locations.

#### Acceptance Criteria

1. WHEN a Customer submits a profile update, THE Customer_Service SHALL accept a display name between 1 and 100 characters, a valid email address, and a profile photo in JPEG or PNG format with a maximum file size of 5 MB, and persist all provided fields.
2. WHEN a Customer submits a new address with GPS coordinates, THE Customer_Service SHALL reverse-geocode the coordinates and store the resolved address alongside the coordinates; IF reverse-geocoding fails, THEN THE Customer_Service SHALL store the raw coordinates and return a warning that the address could not be resolved automatically.
3. WHEN a Customer attempts to save an address that would exceed the 10-address limit, THE Customer_Service SHALL reject the request and return an error indicating the maximum address count has been reached.
4. WHEN a Customer deletes their current default address and at least one other address exists, THE Customer_Service SHALL automatically designate the most recently added remaining address as the new default.
5. WHEN a Customer enables GPS-based location detection, THE Customer_Service SHALL use the device-reported coordinates as the service location without requiring manual address entry; IF the device denies GPS access or coordinates are unavailable, THEN THE Customer_Service SHALL return an error prompting the Customer to enter the address manually.
6. IF a Customer deletes an address that is referenced by an active Booking, THEN THE Customer_Service SHALL reject the deletion and return an error indicating the address is in use by a specific active Booking.
7. THE Customer_Service SHALL store all Customer personally identifiable information in encrypted form at rest, such that the data is not readable without the encryption key.

---

### Requirement 3: Service Catalog Management

**User Story:** As an Admin, I want to manage service categories and subcategories dynamically, so that the platform can offer new services without requiring code changes.

#### Acceptance Criteria

1. THE Service_Catalog_Service SHALL maintain a configurable, database-driven list of Service_Categories and Service_Subcategories with no categories hardcoded in application logic.
2. WHEN an Admin creates a new Service_Category with a name, description, icon, and display order, THE Service_Catalog_Service SHALL persist the category and make it available to all services within 60 seconds.
3. WHEN an Admin creates a new Service_Subcategory under an existing active Service_Category, THE Service_Catalog_Service SHALL persist the subcategory and associate it with the parent category.
4. IF an Admin attempts to create a Service_Subcategory under a non-existent or deactivated parent Service_Category, THEN THE Service_Catalog_Service SHALL reject the request and return an error identifying the invalid parent.
5. WHEN an Admin deactivates a Service_Category, THE Service_Catalog_Service SHALL exclude the category from all catalog listing responses to customer-facing APIs and prevent new Bookings for any subcategory within it; existing active Bookings for subcategories of the deactivated category SHALL not be affected.
6. IF an Admin attempts to delete a Service_Category that has active Providers or active Bookings, THEN THE Service_Catalog_Service SHALL reject the deletion and return an error specifying the count and identifiers of the blocking Providers and Bookings.
7. THE Service_Catalog_Service SHALL support the following configurable attributes per Service_Subcategory: pricing base amount (0.01–999,999.99), estimated duration in minutes (1–480), required skill tags (maximum 20 tags), and an emergency availability flag.
8. WHEN a Service_Catalog_Service response is requested, THE Service_Catalog_Service SHALL return catalog data that reflects the database state as of no more than 300 seconds ago; catalog data updated within the last 300 seconds MAY be served from cache.

---

### Requirement 4: Provider Profile and Skill Management

**User Story:** As a service Provider, I want to set up my profile with my service categories, skills, experience, and service area, so that I am matched to relevant jobs near me.

#### Acceptance Criteria

1. WHEN a Provider submits their profile with service categories, skill tags (1 to 20 tags), years of experience (0 to 50), and a service radius between 1 and 100 kilometers, THE Provider_Service SHALL persist this information and associate it with the Provider account.
2. IF a Provider submits a service radius outside the range of 1 to 100 kilometers, THEN THE Provider_Service SHALL reject the request and return a validation error specifying the permitted range.
3. WHEN a Provider selects service categories and subcategories, THE Provider_Service SHALL allow selection of up to 5 active Service_Categories and up to 10 active Service_Subcategories per category; selection of deactivated categories or subcategories SHALL be rejected with a descriptive error.
4. WHEN a Provider updates their service radius, THE Provider_Service SHALL update the stored radius within 5 seconds.
5. WHEN a Provider sets their general availability schedule, THE Provider_Service SHALL persist day-of-week and hour-of-day slots at 1-hour granularity and reject overlapping slots with a validation error.
6. WHEN a Provider toggles emergency availability, THE Provider_Service SHALL update the emergency availability flag independently of the general availability schedule within 5 seconds.
7. WHEN a Provider's overall rating drops below 3.0 stars and the Provider is not already flagged for review, THE Provider_Service SHALL flag the Provider account for Admin review and emit an internal alert to the Admin team.
8. IF a Provider attempts to select a deactivated Service_Category or Service_Subcategory, THEN THE Provider_Service SHALL reject the selection and return an error identifying the deactivated item.
9. THE Provider_Service SHALL store bank account details for settlement in encrypted form at rest, such that the data is not readable without the encryption key.

---

### Requirement 5: Provider Verification Workflow

**User Story:** As an Admin, I want to review and approve Provider applications with background checks, so that only trustworthy professionals are permitted to take jobs on HomeFix.

#### Acceptance Criteria

1. THE Verification_Service SHALL enforce the Provider verification state machine with the following states and permitted transitions: PENDING → DOCUMENT_SUBMITTED; DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED or REJECTED; DOCUMENT_VERIFIED → BACKGROUND_CHECK_PENDING; BACKGROUND_CHECK_PENDING → BACKGROUND_CHECK_COMPLETED; BACKGROUND_CHECK_COMPLETED → APPROVED or REJECTED; APPROVED → SUSPENDED; SUSPENDED → APPROVED. APPROVED, REJECTED, and SUSPENDED are the only terminal-eligible states.
2. IF a state transition is requested that is not permitted by the defined state machine, THEN THE Verification_Service SHALL reject the transition and return an error identifying the current state and the disallowed target state.
3. WHEN a Provider uploads required documents (Government ID, address proof, skill certification), THE Verification_Service SHALL store documents in object storage with server-side encryption and update the Provider verification status to DOCUMENT_SUBMITTED.
4. WHEN an Admin marks Provider documents as verified, THE Verification_Service SHALL update the verification status to DOCUMENT_VERIFIED and trigger a background check request.
5. WHEN the background check process is initiated, THE Verification_Service SHALL update the Provider status to BACKGROUND_CHECK_PENDING and record the timestamp.
6. WHEN a background check result is received, THE Verification_Service SHALL update the Provider status to BACKGROUND_CHECK_COMPLETED and store the result.
7. WHEN an Admin approves a Provider with BACKGROUND_CHECK_COMPLETED status, THE Verification_Service SHALL update the Provider status to APPROVED and enable the Provider to receive job assignments.
8. WHEN an Admin rejects a Provider, THE Verification_Service SHALL update the Provider status to REJECTED, record the rejection reason, and notify the Provider via the Notification_Service.
9. WHEN an Admin suspends an APPROVED Provider, THE Verification_Service SHALL update the Provider status to SUSPENDED, immediately remove the Provider from the active dispatch pool, and cancel any pending job offers to that Provider.
10. THE Verification_Service SHALL reject job assignment requests for any Provider whose status is not APPROVED.
11. THE Verification_Service SHALL maintain a complete audit trail of all verification status changes including the actor's user ID, timestamp, previous state, new state, and reason.

---

### Requirement 6: Pricing Engine

**User Story:** As a Customer, I want to see a fully transparent price breakdown before confirming a booking, so that I can make an informed decision without fear of hidden charges.

#### Acceptance Criteria

1. THE Pricing_Engine SHALL calculate booking price as: base_price + distance_charge + time_charge + parts_materials_charge + emergency_charge + weekend_surcharge + night_surcharge + demand_surge_charge + platform_fee + applicable_taxes − discount_amount − coupon_amount, where the final total SHALL NOT be less than 0.01.
2. WHEN a Booking is flagged as an Emergency_Service, THE Pricing_Engine SHALL apply an emergency multiplier to the base price, where the multiplier SHALL NOT exceed the configured maximum emergency multiplier (default 2.0x).
3. WHEN platform-wide demand exceeds the configured demand threshold, THE Pricing_Engine SHALL apply a surge multiplier, where the surge multiplier SHALL NOT exceed the configured maximum surge multiplier (default 2.0x).
4. WHEN both emergency and surge multipliers are applicable to the same Booking, THE Pricing_Engine SHALL apply the emergency multiplier first and then apply the surge multiplier to the result, and the combined effective multiplier SHALL NOT exceed the sum of both configured caps.
5. WHEN a Booking is scheduled between 22:00 and 06:00 local time, THE Pricing_Engine SHALL apply the configured night surcharge.
6. WHEN a Booking is scheduled on a Saturday or Sunday, THE Pricing_Engine SHALL apply the configured weekend surcharge.
7. THE Pricing_Engine SHALL calculate distance_charge based on the straight-line distance between the Provider's location and the Customer's location in kilometers, multiplied by the configured per-km rate, where the total distance charge SHALL NOT exceed the configured maximum travel charge.
8. WHEN a Provider adds parts or materials during job execution, THE Pricing_Engine SHALL add the itemized parts cost to the total and transition the Booking to CUSTOMER_APPROVAL_PENDING; IF the Customer does not respond within 10 minutes, THE Pricing_Engine SHALL treat the request as rejected and notify the Provider.
9. THE Pricing_Engine SHALL return a fully itemized price breakdown to the Customer before booking confirmation, showing each component (base price, emergency charge, surge charge, night surcharge, weekend surcharge, distance charge, parts/materials, platform fee, taxes, discounts) separately.
10. WHEN a coupon code is applied, THE Pricing_Engine SHALL validate the coupon's type, expiry date, usage limits, and minimum order value; IF any constraint is violated, THE Pricing_Engine SHALL return a descriptive error identifying the violated constraint.
11. THE Pricing_Engine SHALL allow Admin to configure all pricing parameters (base prices, multiplier caps, surcharges, platform fee, per-km rate) per Service_Subcategory via the Admin portal without code changes, and SHALL apply updated parameters to all new Bookings within 60 seconds.
12. THE Pricing_Engine SHALL support Provider-specific custom pricing overrides; IF a Provider-specific price is outside the platform-defined floor or ceiling for the Service_Subcategory, THE Pricing_Engine SHALL reject the override and return an error specifying the permitted range.

---

### Requirement 7: Booking Creation — Scheduled Service

**User Story:** As a Customer, I want to schedule a home service for a future date and time, so that I can plan maintenance work at my convenience.

#### Acceptance Criteria

1. WHEN a Customer submits a scheduled booking request with service subcategory, address, preferred date, preferred time slot, and optional description, THE Booking_Service SHALL create a Booking with status CREATED and return a unique booking reference number.
2. WHEN a Customer uploads media at booking creation, THE Booking_Service SHALL accept up to 10 files per booking with a maximum size of 50 MB each in JPEG, PNG, MP4, or MOV formats, store them in object storage, and associate the media references with the Booking.
3. WHEN a Customer submits a booking request, THE Booking_Service SHALL request a price estimate from the Pricing_Engine and present the itemized estimate to the Customer before asking for confirmation.
4. IF the Pricing_Engine is unavailable when a booking request is submitted, THEN THE Booking_Service SHALL return an error indicating the service is temporarily unavailable and SHALL NOT create a Booking.
5. WHEN the Customer confirms the Booking and the price estimate, THE Booking_Service SHALL transition the Booking to SEARCHING_PROVIDER and publish a BookingCreated event to Kafka.
6. IF a Customer attempts to create a Booking for a deactivated Service_Category or Service_Subcategory, THEN THE Booking_Service SHALL reject the request and return an error identifying the unavailable service.
7. IF a Customer submits a scheduled booking with a requested time less than 2 hours from the current time, THEN THE Booking_Service SHALL reject the request and return an error indicating the minimum lead time requirement.
8. IF a Customer submits a scheduled booking with a requested time more than 90 days from the current date, THEN THE Booking_Service SHALL reject the request and return an error indicating the maximum scheduling horizon.

---

### Requirement 8: Emergency Service Flow

**User Story:** As a Customer facing a home emergency, I want to get a verified professional dispatched immediately, so that my emergency is resolved as fast as possible.

#### Acceptance Criteria

1. WHEN a Customer triggers an emergency booking with a service category, subcategory, and location, THE Booking_Service SHALL create a Booking with status CREATED and emergency flag set to true within 2 seconds, and then transition the Booking to SEARCHING_PROVIDER within an additional 3 seconds (total within 5 seconds of the request).
2. WHEN an emergency Booking enters SEARCHING_PROVIDER status, THE Dispatch_Engine SHALL identify eligible Providers within a configurable search radius (default 10 km), filtered by APPROVED verification status, at least one matching skill tag, and emergency availability flag enabled.
3. WHEN the Dispatch_Engine identifies eligible Providers, THE Dispatch_Engine SHALL calculate a Matching_Score for each eligible Provider using: distanceScore × 0.30 + availabilityScore × 0.25 + ratingScore × 0.20 + skillScore × 0.15 + performanceScore × 0.10.
4. WHERE the Admin has configured custom matching weights, THE Dispatch_Engine SHALL use the Admin-configured weights instead of the defaults, provided the weights sum to 1.0.
5. THE Dispatch_Engine SHALL send a job offer to the highest-scoring eligible Provider and wait up to a configurable timeout (default 60 seconds) for acceptance; no concurrent duplicate offer SHALL be sent to the same Provider for the same Booking.
6. WHEN a Provider accepts a job offer, THE Booking_Service SHALL transition the Booking from SEARCHING_PROVIDER to PROVIDER_ACCEPTED, record the assigned Provider, and publish a ProviderAccepted event to Kafka within 5 seconds.
7. WHEN a Provider rejects a job offer or the acceptance timeout elapses, THE Dispatch_Engine SHALL record the rejection or timeout, remove that Provider from the candidate pool for this Booking, and send a new job offer to the next-highest-scoring eligible Provider.
8. IF no eligible Provider accepts the job after all candidates within the initial search radius are exhausted, THEN THE Dispatch_Engine SHALL expand the search radius by the configured increment (default 5 km) and repeat the search, up to a maximum of 3 expansion cycles.
9. IF no eligible Provider accepts the job after all expansion cycles are exhausted, THEN THE Booking_Service SHALL transition the Booking to SEARCHING_FAILED status, send the Customer a push notification and SMS explaining that no Provider is available, and alert the Dispatcher team via an internal channel.
10. WHEN a Booking transitions to PROVIDER_ACCEPTED, THE Notification_Service SHALL send the Customer a push notification and SMS containing the Provider's display name, verified badge status, star rating, ETA in minutes, and a masked contact reference within 10 seconds.
11. WHEN the Dispatch_Engine selects a Provider to receive a job offer, THE Dispatch_Engine SHALL acquire an exclusive lock on that Provider's offer slot before sending the offer, such that no two concurrent booking flows can send simultaneous offers to the same Provider.

---

### Requirement 9: Job Lifecycle State Machine

**User Story:** As a platform operator, I want all bookings to follow a strict state machine, so that the system maintains data consistency and every transition is auditable.

#### Acceptance Criteria

1. THE Booking_Service SHALL enforce the Booking state machine with the following permitted transitions: CREATED → SEARCHING_PROVIDER; SEARCHING_PROVIDER → PROVIDER_ASSIGNED or SEARCHING_FAILED or CANCELLED; PROVIDER_ASSIGNED → PROVIDER_ACCEPTED or CANCELLED; PROVIDER_ACCEPTED → PROVIDER_ON_THE_WAY or CANCELLED; PROVIDER_ON_THE_WAY → PROVIDER_ARRIVED or CANCELLED; PROVIDER_ARRIVED → JOB_STARTED; JOB_STARTED → JOB_PAUSED or ADDITIONAL_QUOTE_REQUIRED or JOB_COMPLETED; JOB_PAUSED → JOB_STARTED; ADDITIONAL_QUOTE_REQUIRED → CUSTOMER_APPROVAL_PENDING; CUSTOMER_APPROVAL_PENDING → JOB_STARTED or JOB_COMPLETED; JOB_COMPLETED → CUSTOMER_CONFIRMED or DISPUTED; CUSTOMER_CONFIRMED → PAYMENT_PENDING; PAYMENT_PENDING → PAYMENT_COMPLETED or DISPUTED; PAYMENT_COMPLETED → REFUNDED; DISPUTED → REFUNDED or PAYMENT_COMPLETED. Terminal states with no outgoing transitions: SEARCHING_FAILED, REFUNDED, CANCELLED.
2. IF a state transition is attempted that is not defined in the permitted transition map, THEN THE Booking_Service SHALL reject the transition, log the attempt with the booking ID, attempted source state, attempted target state, and requesting actor, and return a 409 Conflict response.
3. WHEN a Provider, in PROVIDER_ACCEPTED state, marks themselves as on the way, THE Booking_Service SHALL transition the Booking to PROVIDER_ON_THE_WAY and publish a ProviderArriving event to Kafka.
4. WHEN a Provider, in PROVIDER_ON_THE_WAY state, confirms arrival at the Customer's location, THE Booking_Service SHALL transition the Booking to PROVIDER_ARRIVED and publish a ProviderArrived event to Kafka.
5. WHEN a Provider, in PROVIDER_ARRIVED state, starts work, THE Booking_Service SHALL transition the Booking to JOB_STARTED, record the job start timestamp, and publish a JobStarted event to Kafka.
6. WHEN a Provider, in JOB_STARTED state, requests an additional quote, THE Booking_Service SHALL transition the Booking to ADDITIONAL_QUOTE_REQUIRED, request an updated price from the Pricing_Engine, and then transition to CUSTOMER_APPROVAL_PENDING.
7. WHEN a Customer approves an additional quote while the Booking is in CUSTOMER_APPROVAL_PENDING state, THE Booking_Service SHALL transition the Booking back to JOB_STARTED and notify the Provider.
8. IF a Customer rejects an additional quote while the Booking is in CUSTOMER_APPROVAL_PENDING state, THEN THE Booking_Service SHALL transition the Booking to JOB_COMPLETED at the original pre-additional-quote price and notify the Provider.
9. IF a Customer does not respond to an additional quote within 60 minutes of the Booking entering CUSTOMER_APPROVAL_PENDING state, THEN THE Booking_Service SHALL transition the Booking to JOB_COMPLETED at the original price, notify both the Customer and Provider of the auto-resolution, and record the system as the transition actor.
10. WHEN a Provider marks a job as complete with at least one after-photo attached, THE Booking_Service SHALL transition the Booking to JOB_COMPLETED and publish a JobCompleted event to Kafka.
11. IF a Provider attempts to mark a job as complete without attaching at least one after-photo, THEN THE Booking_Service SHALL reject the transition and return an error requiring a photo upload.
12. WHEN a Customer confirms job completion, THE Booking_Service SHALL transition the Booking to CUSTOMER_CONFIRMED.
13. WHEN the Booking transitions to CUSTOMER_CONFIRMED, THE Booking_Service SHALL immediately and automatically transition the Booking to PAYMENT_PENDING without requiring further actor input, recording the system as the transition actor.
14. WHEN payment is successfully processed, THE Booking_Service SHALL transition the Booking to PAYMENT_COMPLETED and publish a PaymentCompleted event to Kafka.
15. THE Booking_Service SHALL record the timestamp and actor (user ID + role for human actors, service name for system-initiated transitions) for every state transition in the Booking audit trail.
16. IF a Booking is cancelled while in CREATED, SEARCHING_PROVIDER, PROVIDER_ASSIGNED, or PROVIDER_ACCEPTED state, THEN THE Booking_Service SHALL transition to CANCELLED with no cancellation fee applied.
17. IF a Booking is cancelled while in PROVIDER_ON_THE_WAY, PROVIDER_ARRIVED, or JOB_STARTED state, THEN THE Booking_Service SHALL apply the configured cancellation fee and initiate partial refund processing for the remainder.
18. IF a Booking is in PROVIDER_ON_THE_WAY state and a cancellation is requested, THE Booking_Service SHALL apply the cancellation fee configured for that Service_Subcategory in the range 0.00–999.99 in the Booking's currency.

---

### Requirement 10: Real-Time Provider Location Tracking

**User Story:** As a Customer, I want to see my Provider's real-time location on a map after they accept my booking, so that I know exactly when to expect them.

#### Acceptance Criteria

1. WHEN a Provider's Booking status is PROVIDER_ACCEPTED or any subsequent active state, THE Location_Service SHALL accept Provider location updates submitted at a rate no greater than one update every 5 seconds per Provider per active Booking.
2. WHEN the Location_Service receives a Provider location update, THE Location_Service SHALL store the coordinates in a low-latency cache and push the update to all Customers subscribed to that Booking's location feed.
3. WHEN a Customer opens the tracking view for an active Booking, THE Location_Service SHALL deliver the most recently stored Provider coordinates within 2 seconds.
4. WHEN the Location_Service receives a Provider location update, THE Location_Service SHALL calculate the updated ETA in minutes from the Provider's current coordinates to the Customer's service address and push the updated ETA to the subscribed Customer.
5. WHEN a Booking transitions to JOB_STARTED status, THE Location_Service SHALL terminate all active location update subscriptions for that Booking's Customers and stop accepting location updates for that Booking.
6. THE Location_Service SHALL persist the complete Provider location history for each active Booking to durable storage for dispute resolution purposes.
7. IF a Customer requests the tracking view for an active Booking and no location update has been received within the last 60 seconds, THE Location_Service SHALL return the last known coordinates along with the timestamp of the last update, so the Customer is informed the location may be stale.

---

### Requirement 11: Provider Job Execution

**User Story:** As a Provider, I want a clear digital workflow for executing a job including before/after photos, parts tracking, and completion, so that I can provide professional service and get paid accurately.

#### Acceptance Criteria

1. WHEN a Provider navigates to an assigned job, THE Provider_Service SHALL display the Customer's display name, service address, service description, Customer-uploaded media references, and a navigation deep-link to the Customer's location coordinates.
2. WHEN a Provider attempts to transition a Booking to JOB_STARTED, THE Booking_Service SHALL require at least one before-photo to be attached to the Booking; IF no before-photo is attached, THEN the transition SHALL be rejected with an error requiring photo upload.
3. WHEN a Provider adds a parts or materials line item during job execution, THE Booking_Service SHALL record the item name, quantity (minimum 1), and unit cost (minimum 0.01), and submit the updated parts total to the Pricing_Engine for recalculation.
4. WHEN a Provider attempts to transition a Booking to JOB_COMPLETED, THE Booking_Service SHALL require at least one after-photo to be attached to the Booking; IF no after-photo is attached, THEN the transition SHALL be rejected with an error requiring photo upload.
5. WHEN a Provider pauses a job, THE Booking_Service SHALL require a mandatory reason (1 to 500 characters) and transition the Booking to JOB_PAUSED; WHEN the Provider resumes, THE Booking_Service SHALL transition the Booking back to JOB_STARTED and record the pause duration.
6. WHEN a job is marked complete, THE Booking_Service SHALL calculate the final job duration as the sum of all JOB_STARTED intervals minus all JOB_PAUSED intervals from the first JOB_STARTED timestamp to the JOB_COMPLETED timestamp, and store this net duration with the Booking record.

---

### Requirement 12: Payment Processing

**User Story:** As a Customer, I want to pay for services securely using my preferred payment method, so that I can complete transactions with confidence.

#### Acceptance Criteria

1. THE Payment_Service SHALL support multiple payment gateways through an abstracted interface, such that adding a new payment gateway requires only a new implementation of that interface with no changes to core business logic.
2. THE Payment_Service SHALL support the following payment methods: UPI, credit/debit card, net banking, in-platform wallet, and cash.
3. WHEN a payment is initiated for a specific Customer and Booking, THE Payment_Service SHALL generate a unique Idempotency_Key scoped to that Customer and Booking combination; IF a subsequent payment attempt is made with the same Idempotency_Key, THE Payment_Service SHALL reject the duplicate and return the status of the original transaction.
4. THE Payment_Service SHALL maintain each transaction in one of the following states with the following permitted transitions: PENDING → SUCCESS or FAILED; SUCCESS → REFUNDED or PARTIALLY_REFUNDED; FAILED is a terminal state. No other transitions are permitted.
5. WHEN a payment gateway callback is received, THE Payment_Service SHALL verify the callback's cryptographic signature before updating the transaction state; IF the signature is invalid, THE Payment_Service SHALL reject the callback and log the incident.
6. WHEN a payment transitions to SUCCESS, THE Payment_Service SHALL publish a PaymentCompleted event to Kafka and trigger invoice generation within 30 seconds; IF either the Kafka publish or invoice trigger fails, THE Payment_Service SHALL retry up to 3 times with exponential backoff before logging a CRITICAL error for manual intervention.
7. WHEN a refund is approved, THE Payment_Service SHALL initiate the refund via the payment gateway and update the transaction state to REFUNDED or PARTIALLY_REFUNDED within 5 minutes of approval; IF the payment gateway refund call fails, THE Payment_Service SHALL log the failure and alert the Finance_Admin team for manual processing.
8. IF a payment attempt fails, THEN THE Payment_Service SHALL record the failure reason and allow up to 3 retry attempts by the Customer before marking the transaction as permanently FAILED.
9. THE Payment_Service SHALL not store raw card numbers; all stored payment credentials SHALL be encrypted at rest such that they are not readable without the encryption key.
10. WHEN a payment transitions to SUCCESS, THE Payment_Service SHALL calculate the Provider's net earnings as the total payment amount minus the platform fee percentage defined in the Pricing_Engine configuration, and credit this amount to the Provider's Wallet within 60 seconds.
11. IF the Wallet credit operation fails, THEN THE Payment_Service SHALL log the failure with the booking ID and amount, and retry the credit up to 3 times before alerting the Finance_Admin team for manual reconciliation.

---

### Requirement 13: Invoice Generation

**User Story:** As a Customer, I want to receive a detailed invoice after each completed service, so that I have a formal record of the work done and charges paid.

#### Acceptance Criteria

1. WHEN a PaymentCompleted event is consumed, THE Invoice_Service SHALL generate a PDF invoice containing: invoice number, invoice date, Customer full name and address, Provider full name and verified status, service description, itemized price breakdown with each component labeled, tax amount and applicable tax identifier, discount amount (omitted if zero), final total amount, and payment method used.
2. WHEN an invoice PDF is generated, THE Invoice_Service SHALL store the PDF in durable object storage with server-side encryption.
3. WHEN an invoice PDF is stored, THE Invoice_Service SHALL deliver a signed URL with a 72-hour expiry to the Customer via the Notification_Service within 60 seconds of the PaymentCompleted event.
4. THE Invoice_Service SHALL assign invoice numbers in the format INV-YYYY-MM-NNNNNN, where YYYY is the year, MM is the zero-padded month, and NNNNNN is a zero-padded monotonically increasing sequence number per month, such that each invoice number is globally unique and non-guessable from sequential enumeration alone.
5. THE Invoice_Service SHALL make all past invoices for a Customer accessible via the Customer's service history for a minimum of 24 months from the invoice date.
6. THE Invoice_Service SHALL make Provider earnings statements accessible to Providers on the first calendar day of the following month, summarizing all settled jobs, gross earnings, platform fees deducted, and net payout for the preceding month.
7. IF PDF generation fails, THEN THE Invoice_Service SHALL log the failure with the booking ID and payment ID, retry up to 3 times, and alert the operations team if all retries fail, without preventing the Booking from completing its payment flow.

---

### Requirement 14: Provider Earnings and Settlement

**User Story:** As a Provider, I want to track my earnings and receive timely settlements, so that I can manage my finances reliably.

#### Acceptance Criteria

1. THE Provider_Service SHALL maintain a Wallet balance for each Provider reflecting cumulative earnings from completed jobs, minus settled amounts and any deductions; permitted deduction types are platform fee and complaint-related penalty, both of which SHALL be itemized separately in earnings history.
2. WHEN a Provider requests a settlement, THE Provider_Service SHALL validate that: (a) the requested amount is between 1.00 and the current available Wallet balance, and (b) the Provider has at least one verified bank account on file; IF either condition is not met, THE Provider_Service SHALL reject the request with a descriptive error identifying the failed condition.
3. WHEN a settlement request is validated, THE Payment_Service SHALL initiate a bank transfer to the Provider's verified registered account within 2 business days and update the settlement status to one of: PENDING, PROCESSING, COMPLETED, FAILED.
4. IF the bank transfer fails, THEN THE Payment_Service SHALL update the settlement status to FAILED, credit the settlement amount back to the Provider's Wallet, notify the Provider via push notification and SMS, and alert the Finance_Admin team for manual review.
5. THE Provider_Service SHALL provide a Provider with a paginated earnings history showing each job's booking reference, gross earnings, platform fee deducted, and net earning per job.
6. WHEN a settlement is completed, THE Notification_Service SHALL send the Provider an SMS and push notification confirming the settlement amount and the expected bank credit date.
7. THE Finance_Admin role SHALL have access to review, approve, and reject settlement requests, providing a rejection reason when rejecting; all Finance_Admin actions on settlement requests SHALL be recorded in the Audit_Log.

---

### Requirement 15: Ratings and Reviews

**User Story:** As a Customer, I want to rate and review my Provider after a service, so that the community can make informed decisions and quality standards are upheld.

#### Acceptance Criteria

1. WHEN a Booking transitions to PAYMENT_COMPLETED, THE Rating_Review_Service SHALL create a review prompt for the Customer that remains open for 7 calendar days from the PAYMENT_COMPLETED timestamp.
2. IF a Customer attempts to submit a review after the 7-calendar-day window has expired, THEN THE Rating_Review_Service SHALL reject the submission and return an error indicating the review period has closed.
3. WHEN a Customer submits a review within the open window, THE Rating_Review_Service SHALL record integer star ratings (1–5) on 5 dimensions: overall, behavior, quality, timeliness, and pricing_transparency; an optional text review up to 1000 characters; and up to 5 photo attachments each no larger than 10 MB.
4. WHEN a review is applied, THE Rating_Review_Service SHALL recalculate the Provider's aggregate rating on a scale of 1.0–5.0 (rounded to 2 decimal places) using a weighted formula that applies a 1.5x weight multiplier to reviews submitted within the last 90 days relative to reviews older than 90 days.
5. WHEN the Rating_Review_Service detects any of the following suspicious patterns for a review, THE Rating_Review_Service SHALL flag the review for Admin moderation and SHALL NOT apply it to the Provider's aggregate score until an Admin approves it: (a) two or more reviews from the same IP address within 1 hour; (b) a star rating deviating more than 2 standard deviations from the Provider's historical mean; (c) a review submitted from an account created within 24 hours of the review submission.
6. THE Rating_Review_Service SHALL publish a ReviewSubmitted event to Kafka upon successful submission of a non-flagged review.
7. WHEN a Provider's aggregate rating falls below the platform-configured threshold (default 3.0 stars), THE Rating_Review_Service SHALL notify the Admin team via an internal alert.
8. WHEN a Provider's aggregate rating falls below the platform-configured threshold (default 3.0 stars) and the Provider is not already in UNDER_REVIEW status, THE Rating_Review_Service SHALL flag the Provider account with UNDER_REVIEW status.
9. WHEN an Admin removes a review for violating platform policies (flagged prohibited content, verified fraudulent activity, or confirmed spam), THE Rating_Review_Service SHALL delete the review, recalculate the Provider's aggregate rating, log the removal with the Admin's user ID and the removal reason in the Audit_Log.
10. WHEN a Booking transitions to PAYMENT_COMPLETED, THE Rating_Review_Service SHALL create a review prompt for the Provider to rate the Customer on a 1–5 integer star scale, which remains open for 7 calendar days.

---

### Requirement 16: Complaints and Dispute Resolution

**User Story:** As a Customer, I want to raise a complaint about a service or Provider, so that platform issues are addressed and I can receive a fair resolution including refunds when warranted.

#### Acceptance Criteria

1. WHEN a Customer raises a complaint against a completed Booking, THE Complaint_Service SHALL create a complaint record with the Booking reference, complaint category (one of: poor quality, late arrival, overcharging, unprofessional behavior, incomplete work, damage, payment issue), a description up to 2000 characters, and up to 5 evidence attachments each no larger than 10 MB, and assign it to an available Support_Agent.
2. WHEN a complaint is created, THE Complaint_Service SHALL acknowledge the complaint to the Customer via the Notification_Service within 30 minutes of submission.
3. WHEN a Support_Agent updates the complaint status, THE Complaint_Service SHALL notify the Customer of the status change via in-app notification within 5 minutes.
4. THE Complaint_Service SHALL enforce the following resolution SLA: 24 hours for emergency service complaints, 72 hours for standard complaints; IF the SLA is breached, THE Complaint_Service SHALL escalate the complaint to a Senior_Support_Agent and send the Customer a notification acknowledging the delay.
5. WHEN a Support_Agent approves a refund for a complaint, THE Complaint_Service SHALL submit a refund request to the Payment_Service and update the Booking status to REFUNDED.
6. IF the Payment_Service rejects the refund request, THEN THE Complaint_Service SHALL set the complaint status to REFUND_FAILED, notify the Customer, and alert the Finance_Admin team for manual processing.
7. WHEN a Support_Agent sets a Booking to DISPUTED status, THE Booking_Service SHALL place a hold on the associated Provider settlement until the complaint is closed.
8. WHEN a complaint is resolved or closed, THE Booking_Service SHALL release the Provider settlement hold for any affected settlement amounts not subject to the refund.
9. THE Complaint_Service SHALL make complaint category counts, resolution rates, and average resolution times available to Admin reports updated at most 60 minutes after each complaint status change.

---

### Requirement 17: Notification System

**User Story:** As a user, I want to receive timely, relevant notifications via push, SMS, and email at each stage of my service, so that I am always informed of what is happening.

#### Acceptance Criteria

1. THE Notification_Service SHALL support the following notification channels: push notification, SMS, email, and in-app notification.
2. THE Notification_Service SHALL abstract SMS delivery behind a provider interface such that the SMS vendor can be changed without modifying notification business logic.
3. THE Notification_Service SHALL abstract email delivery behind a provider interface such that the email vendor can be changed without modifying notification business logic.
4. WHEN a Kafka event is consumed by the Notification_Service, THE Notification_Service SHALL dispatch the appropriate notification to the relevant user within 10 seconds.
5. THE Notification_Service SHALL send notifications for the following events: BookingCreated, ProviderAssigned, ProviderAccepted, ProviderRejected, ProviderArriving, ProviderArrived, JobStarted, JobCompleted, PaymentCompleted, BookingCancelled, ReviewSubmitted.
6. THE Notification_Service SHALL respect user notification preferences and SHALL NOT send a notification via a channel the user has disabled; IF user preference data is unavailable at dispatch time, THE Notification_Service SHALL default to treating all channels as enabled.
7. THE Notification_Service SHALL maintain a delivery log for each notification attempt recording: Kafka event ID, channel, user ID, timestamp, delivery status, retry count, and error description if applicable; each delivery attempt SHALL be keyed on the Kafka event ID and channel to prevent duplicate delivery when the same event is processed more than once.
8. IF a push, SMS, or email notification delivery fails, THEN THE Notification_Service SHALL retry delivery up to 3 times with exponential backoff at intervals of 1 second, 2 seconds, and 4 seconds before marking the delivery as permanently failed.

---

### Requirement 18: In-App Chat

**User Story:** As a Customer or Provider, I want to communicate in real time through in-app chat during an active booking, so that we can coordinate without sharing personal phone numbers.

#### Acceptance Criteria

1. WHEN a Booking transitions to PROVIDER_ACCEPTED status, THE Chat_Service SHALL activate a chat channel between the Customer and Provider for that Booking.
2. WHEN a message is sent via the Chat_Service, THE Chat_Service SHALL deliver it to the recipient within 1 second under a concurrency load of up to 500 concurrent active chat channels.
3. IF the recipient is not connected at the time a message is sent, THEN THE Chat_Service SHALL send a push notification informing the recipient of the unread message within 10 seconds.
4. THE Chat_Service SHALL store all messages for a Booking for a minimum of 90 days from the Booking's creation date, regardless of channel deactivation status.
5. WHEN a Booking transitions to PAYMENT_COMPLETED or CANCELLED, THE Chat_Service SHALL deactivate the chat channel for that Booking.
6. IF a user attempts to send a message to a deactivated chat channel, THEN THE Chat_Service SHALL reject the message and return an error indicating the channel is no longer active.
7. THE Chat_Service SHALL restrict channel participation to only the Customer and Provider linked to the Booking; any attempt to access the channel by any other user SHALL be rejected with a 403 Forbidden response.
8. THE Chat_Service SHALL not expose the Customer's or Provider's personal phone number within the chat interface.

---

### Requirement 19: Admin Dashboard and Operations Portal

**User Story:** As an Admin, I want a comprehensive dashboard with real-time metrics and operational controls, so that I can monitor platform health and intervene when needed.

#### Acceptance Criteria

1. THE Admin_Service SHALL provide a dashboard displaying the following metrics refreshed every 60 seconds: total active Bookings, total active Providers online, new Customer registrations in the last 24 hours, gross revenue in the last 24 hours, average provider response time over the last 24 hours, open complaint count, and overall platform rating (mean of all Review ratings on a 1.0–5.0 scale).
2. THE Admin_Service SHALL provide the following operational modules: User Management, Provider Management, Verification Queue, Service Category Management, Pricing Configuration, Dispatch Rule Configuration, Booking Management, Payment and Refund Management, Complaint Management, Review Moderation, Coupon Management, Notification Templates, Report Generation, Audit Logs, and System Configuration.
3. WHEN an Admin views the Verification Queue, THE Admin_Service SHALL list all Providers in DOCUMENT_SUBMITTED status sorted by submission date oldest first, with the ability to view each submitted document inline within the dashboard without requiring a separate download.
4. WHEN an Admin updates a Pricing configuration parameter, THE Pricing_Engine SHALL apply the updated parameter to all new Bookings within 60 seconds without requiring a service restart.
5. WHEN an Admin submits updated dispatch matching weights, THE Dispatch_Engine SHALL validate that each individual weight is in the range 0.0–1.0 and that all weights sum to exactly 1.0; IF either condition fails, THE Dispatch_Engine SHALL reject the update and return an error identifying which condition failed, leaving the existing weights unchanged.
6. THE Admin_Service SHALL enforce RBAC such that users with the ADMIN role can access all modules except System Configuration; users with the SUPER_ADMIN role can access all modules including System Configuration.
7. IF a user with the ADMIN role attempts to access the System Configuration module, THEN THE Admin_Service SHALL return a 403 Forbidden response.
8. THE Admin_Service SHALL log every Admin action (create, update, delete, approve, reject) in the Audit_Log with the Admin's user ID, action type, affected entity type and ID, timestamp, and: for create actions the initial field values, for update actions the before and after values of each modified field, and for delete actions the field values at time of deletion.

---

### Requirement 20: Reporting and Analytics

**User Story:** As a Finance_Admin or Admin, I want to generate reports on bookings, revenue, provider performance, and customer activity, so that I can make data-driven decisions.

#### Acceptance Criteria

1. THE Reporting_Service SHALL provide the following pre-built reports: Daily/Weekly/Monthly Revenue Summary, Provider Performance Report, Service Category Demand Report, Customer Retention Report, Complaint Resolution Report, and Payment Reconciliation Report.
2. WHEN a report request covers a date range of 7 days or fewer, THE Reporting_Service SHALL return the report synchronously within 10 seconds; IF the result set is empty, THE Reporting_Service SHALL return an empty report with a message indicating no data matches the criteria.
3. WHEN a report is requested with a date range exceeding 7 days, THE Reporting_Service SHALL generate the report asynchronously and notify the requestor via email when the report is ready for download; the download link SHALL expire after 7 days.
4. WHEN an Admin requests a report, THE Reporting_Service SHALL allow filtering by date range, Service_Category, geographic region, and Provider; reports SHALL be exportable in PDF and CSV formats.
5. THE Reporting_Service SHALL store raw event data in an analytics data store for ad-hoc querying with data retained for a minimum of 2 years.
6. THE Finance_Admin role SHALL have exclusive access to Payment Reconciliation Reports and Settlement Reports; IF a user without the Finance_Admin role requests these report types, THE Reporting_Service SHALL return a 403 Forbidden response.

---

### Requirement 21: Coupon and Promotion Management

**User Story:** As an Admin, I want to create and manage coupon codes and promotional campaigns, so that I can drive customer acquisition and retention.

#### Acceptance Criteria

1. WHEN an Admin creates a coupon, THE Promotion_Coupon_Service SHALL persist the coupon with the following required attributes: unique code (case-insensitive, 4–20 alphanumeric characters), discount type (FLAT or PERCENTAGE), discount value (greater than 0), minimum order value (0 or greater), maximum discount cap (required for PERCENTAGE type), valid_from date, expiry date (must be after valid_from), per-user usage limit (minimum 1), and total usage limit (minimum 1).
2. WHEN a Customer applies a coupon code at checkout, THE Promotion_Coupon_Service SHALL validate the coupon against all constraints (active status, date range, minimum order value, per-user usage limit, total usage limit) and return the applicable discount amount; IF any constraint is violated, THE Promotion_Coupon_Service SHALL return a descriptive error identifying the specific violated constraint.
3. WHEN a coupon is successfully applied to a Booking, THE Promotion_Coupon_Service SHALL atomically increment both the total usage counter and the per-user usage counter to prevent over-redemption under concurrent load.
4. WHEN an Admin deactivates a coupon, THE Promotion_Coupon_Service SHALL immediately prevent further redemptions of that coupon; in-flight redemptions already past validation SHALL be honoured.
5. WHEN a Booking using a coupon is cancelled before payment is captured, THE Promotion_Coupon_Service SHALL atomically decrement both the total usage counter and the per-user usage counter to restore the coupon availability.

---

### Requirement 22: Kafka Event-Driven Architecture

**User Story:** As the platform architect, I want all cross-service state changes to be communicated via Kafka events, so that services are loosely coupled and the system is resilient to individual service failures.

#### Acceptance Criteria

1. WHEN a Booking state transition occurs, THE Booking_Service SHALL publish the corresponding event to Kafka: CREATED → BookingCreated; PROVIDER_ASSIGNED → ProviderAssigned; PROVIDER_ACCEPTED → ProviderAccepted; PROVIDER_REJECTED → ProviderRejected; PROVIDER_ON_THE_WAY → ProviderArriving; PROVIDER_ARRIVED → ProviderArrived; JOB_STARTED → JobStarted; JOB_COMPLETED → JobCompleted; PAYMENT_COMPLETED → PaymentCompleted; CANCELLED → BookingCancelled; ReviewSubmitted after review creation.
2. THE Booking_Service SHALL use the Transactional Outbox pattern to ensure each event is written to the outbox table within the same database transaction as the state change, guaranteeing at-least-once delivery.
3. WHEN an Outbox processor successfully publishes an event to Kafka and receives broker acknowledgment, THE Booking_Service SHALL mark the outbox entry as published.
4. IF an outbox event fails to publish, THEN THE Outbox_Processor SHALL retry with exponential backoff starting at 1 second, doubling each attempt up to a maximum interval of 60 seconds, for up to the configured maximum retry count (default 10), before emitting an alert containing the event ID, Kafka topic, and total attempt count.
5. THE Notification_Service, Dispatch_Engine, Location_Service, and Rating_Review_Service SHALL process each consumed Kafka event idempotently by persisting the event ID upon first successful processing and discarding any subsequent delivery of the same event ID without reprocessing.
6. IF a consumer fails to process an event after 3 retry attempts with a 5-second delay between attempts, THEN THE consumer SHALL publish the event to a designated dead-letter topic for that consumer group and continue processing subsequent events.
7. THE platform SHALL define and maintain a versioned event schema for each event type in a shared event-schemas repository; consumers built against schema version N SHALL be able to process version N+1 events without error, ensuring forward compatibility.

---

### Requirement 23: API Gateway and Security

**User Story:** As a security architect, I want all client requests to pass through a secured API Gateway with rate limiting and WAF protection, so that the platform is resilient against abuse and attacks.

#### Acceptance Criteria

1. IF a request arrives at the API_Gateway without a valid JWT, THEN THE API_Gateway SHALL reject the request with a 401 Unauthorized response before routing to any microservice.
2. WHEN a request arrives at the API_Gateway with a valid JWT, THE API_Gateway SHALL route the request to the appropriate microservice based on the request path and HTTP method.
3. THE API_Gateway SHALL enforce per-user rate limiting of a maximum of 100 requests per minute for users with the CUSTOMER role and 60 requests per minute for users with the PROVIDER role.
4. THE API_Gateway SHALL enforce per-phone-number rate limiting on OTP generation endpoints of a maximum of 5 requests per phone number per hour.
5. WHEN a request exceeds the rate limit, THE API_Gateway SHALL return a 429 Too Many Requests response with a Retry-After header indicating the number of seconds until the rate limit resets.
6. THE WAF SHALL block requests matching OWASP Top 10 attack patterns including SQL injection and cross-site scripting (XSS) and return a 400 Bad Request response with a generic error body that does not reveal internal system details.
7. THE API_Gateway SHALL reject plain HTTP requests with a 301 redirect to the equivalent HTTPS URL.
8. THE API_Gateway SHALL enforce HTTPS for all connections, rejecting TLS versions below 1.2.
9. THE API_Gateway SHALL attach a unique X-Correlation-ID header to every routed request; if the incoming request already contains an X-Correlation-ID header, THE API_Gateway SHALL use the existing value; if not, it SHALL generate a new UUID v4 value.
10. THE Auth_Service SHALL implement all authentication flows in compliance with OAuth2/OIDC standards.

---

### Requirement 24: Resilience and Fault Tolerance

**User Story:** As a platform engineer, I want every microservice to implement resilience patterns, so that partial failures do not cascade into full platform outages.

#### Acceptance Criteria

1. EVERY microservice SHALL implement a Circuit Breaker on all synchronous calls to external services and other microservices, configured with a failure rate threshold of 50% over a sliding window of 10 calls and a wait duration in open state of 30 seconds before transitioning to half-open.
2. EVERY microservice SHALL implement retry with exponential backoff for transient failures — defined as connection timeouts, read timeouts, and HTTP 5xx responses — with a maximum of 3 retry attempts, an initial backoff of 500 milliseconds, and a maximum backoff of 8 seconds.
3. EVERY microservice SHALL enforce a timeout on all outbound HTTP calls: a maximum of 5 seconds for critical-path calls in the booking or dispatch request path, and a maximum of 15 seconds for all other outbound calls.
4. WHEN a Circuit Breaker transitions to the open state, THE affected microservice SHALL return a fallback response that indicates service degradation to the caller and SHALL emit a WARN-level log event identifying the affected downstream dependency by name.
5. THE Dispatch_Engine SHALL implement a bulkhead pattern that allocates a dedicated, non-shared thread pool for emergency dispatch processing, separate from the thread pool used for scheduled booking processing, such that exhaustion of the scheduled booking thread pool cannot reduce the threads available for emergency dispatch.
6. THE Booking_Service SHALL implement the Saga pattern for the end-to-end booking flow, recording each completed step in the Saga log before proceeding to the next step.
7. IF any step in the Booking_Service Saga fails after one or more previous steps have been committed, THEN THE Booking_Service SHALL execute compensating transactions for all previously committed steps in reverse order, completing each compensating transaction within 30 seconds, and SHALL return a response to the caller indicating the booking request was not completed.

---

### Requirement 25: Observability and Monitoring

**User Story:** As a platform engineer, I want full observability into system health, errors, and performance, so that I can detect and resolve issues before they impact users.

#### Acceptance Criteria

1. EVERY microservice SHALL emit structured JSON logs to stdout with the following mandatory fields: timestamp (ISO 8601), service name, trace ID, span ID, log level, human-readable message, and any relevant entity IDs (e.g., bookingId, userId).
2. EVERY microservice SHALL instrument all inbound and outbound HTTP requests and all Kafka message processing with OpenTelemetry spans, including the operation name, duration, status, and relevant entity IDs as span attributes.
3. EVERY microservice SHALL expose a GET /health/liveness endpoint returning HTTP 200 when the process is alive, and a GET /health/readiness endpoint returning HTTP 200 when the service is ready to handle traffic and HTTP 503 when it is not.
4. EVERY microservice SHALL expose Prometheus-compatible metrics at a /metrics endpoint including: request rate, error rate, and response time at p50, p95, and p99 percentiles, and Circuit Breaker state (CLOSED, OPEN, HALF_OPEN) per dependency.
5. THE platform SHALL maintain Grafana dashboards with data freshness no older than 60 seconds covering: per-service request rate, error rate, and latency percentiles; Kafka consumer lag per consumer group; database connection pool utilization; cache hit rate; and end-to-end booking funnel conversion rate.
6. WHEN a service's p99 response time exceeds 2 seconds or its error rate exceeds 1% over any 5-minute window, THE monitoring system SHALL trigger an alert to the on-call team within 5 minutes of the threshold being crossed.

---

### Requirement 26: Data Security and Compliance

**User Story:** As a compliance officer, I want all sensitive data to be encrypted and access-controlled, so that the platform meets security and privacy standards.

#### Acceptance Criteria

1. THE platform SHALL encrypt all data at rest in primary storage, database backups, and volume snapshots using AES-256 encryption with keys managed by a cloud key management service.
2. THE platform SHALL enforce TLS 1.2 or higher for all data in transit between clients, API Gateway, and microservices; connections using TLS versions below 1.2 SHALL be rejected.
3. THE platform SHALL store all Customer and Provider personally identifiable information only in designated encrypted fields, with service-level access restricted to services that have been explicitly granted access via a service-level access policy.
4. THE platform SHALL not write any of the following PII to application logs or distributed traces: full name, email address, phone number, national identification number, payment card number, or precise street address.
5. THE Auth_Service SHALL hash all stored passwords using bcrypt with a minimum cost factor of 12.
6. THE platform SHALL implement field-level encryption for all stored payment card data using cloud KMS-managed keys such that raw card numbers are never persisted in any storage tier.
7. THE platform SHALL handle payment card data in compliance with PCI-DSS requirements, including scoping, network segmentation, and access controls applicable to cardholder data environments.
8. WHEN a Customer requests deletion of their account and personal data, THE Customer_Service SHALL acknowledge the request within 24 hours.
9. WHEN a Customer's data deletion request is acknowledged, THE Customer_Service SHALL replace all PII fields (full name, email, phone, address, national ID) with non-reversible anonymized tokens such that the original values cannot be reconstructed, within 30 days of the acknowledgment.
10. THE Audit_Log SHALL be stored in a tamper-evident, append-only data store with write access restricted to authorized service accounts only; no human user account SHALL have direct write access to the Audit_Log store.

---

### Requirement 27: Infrastructure and Deployment

**User Story:** As a DevOps engineer, I want the platform to be deployable on AWS with infrastructure-as-code, so that environments are reproducible, scalable, and auditable.

#### Acceptance Criteria

1. THE platform infrastructure SHALL be fully defined in Terraform with no manual cloud console changes required to provision a complete, functional environment from scratch.
2. EVERY microservice SHALL be packaged as a Docker container image and deployed as a Kubernetes Deployment on AWS EKS.
3. EVERY microservice SHALL define a Kubernetes Horizontal Pod Autoscaler targeting 70% CPU utilization; services that consume Kafka SHALL additionally define autoscaling rules based on consumer lag metrics.
4. THE platform SHALL maintain logically isolated environments for development, staging, and production using separate AWS accounts or Kubernetes namespaces with no shared compute or data resources between environments.
5. EACH microservice SHALL own its own PostgreSQL schema or database instance with no other microservice permitted to read from or write to it directly; all cross-service data access SHALL occur via API calls.
6. THE platform SHALL use a managed Redis cluster for: session token storage, distributed locking, Provider availability caching, Provider location caching, and service catalog caching.
7. THE platform SHALL use AWS S3 with server-side encryption enabled for storing: Provider verification documents, job before/after photos, generated PDF invoices, and Customer-uploaded media.
8. THE platform SHALL use AWS Secrets Manager for all secrets and credentials; no secret values SHALL be stored in source code, container images, environment variable files, or Kubernetes ConfigMaps.
9. EVERY microservice promotion from staging to production SHALL be gated on passing a suite of automated integration tests executed against the staging environment.

---

### Requirement 28: Frontend Applications

**User Story:** As a user, I want a fast, responsive, and accessible web application, so that I can use HomeFix efficiently on any device.

#### Acceptance Criteria

1. THE Customer_App SHALL be a React + TypeScript application built with Vite, using React Router for navigation, TanStack Query for server state management, Zustand or Redux Toolkit for client-side state, Material UI or equivalent for components, and React Hook Form with Zod for form validation.
2. THE Provider_App SHALL be built with the same technology stack as the Customer_App, with a mobile-first layout optimized for Provider workflows on mobile screen sizes.
3. THE Admin_App SHALL be built with the same technology stack, with a desktop-first layout optimized for administrative workflows on large screen sizes.
4. THE Customer_App SHALL achieve a Google Lighthouse performance score of 80 or above on a mobile device profile.
5. EVERY page in all three frontend applications SHALL meet WCAG 2.1 AA accessibility standards; full validation requires manual testing with assistive technologies in addition to automated checks.
6. THE Customer_App SHALL display each available Provider in a card showing: VERIFIED badge (when the Provider has APPROVED verification status), Provider display name, aggregate star rating, total jobs completed count, distance in kilometers, ETA in minutes, and starting price for the requested service.
7. THE Customer_App SHALL implement the following screens: Splash, Login/OTP, Home with service categories, Subcategory selection, Service Request with photo upload, Price Estimate breakdown, Available Professionals list, Live Tracking map, and Service History.
8. THE Provider_App SHALL implement the following screens: Login/OTP, Dashboard with earnings summary and active job list, Job Request with accept/decline, Job Details, Active Job with photo upload and parts entry, Job Completion, Earnings and settlement history, and Profile/Verification status.

---

### Requirement 29: Documentation

**User Story:** As a developer or architect, I want comprehensive documentation, so that I can onboard, develop, deploy, and operate the HomeFix platform effectively.

#### Acceptance Criteria

1. THE platform repository SHALL include the following documentation files: README.md (project overview and quick start), Architecture.md (system design and diagrams), API Documentation (OpenAPI 3.0 specs), Database Design (entity-relationship diagrams), Kafka Event Catalog (event schemas and producer/consumer mapping), Security Architecture, AWS Architecture, Deployment Guide, Local Development Guide, Troubleshooting Guide, and Architecture Decision Records (ADRs) for significant design choices.
2. THE Architecture.md SHALL include Mermaid diagrams covering: system context (C4 Level 1), container view (C4 Level 2), microservice interaction, booking flow, emergency dispatch flow, payment flow, provider verification flow, and AWS deployment topology.
3. THE Kafka Event Catalog SHALL document each event type with: event name, producing service, all consuming services, versioned schema definition, and a concrete example payload.
4. THE API Documentation SHALL follow the OpenAPI 3.0 specification and SHALL be served via a Swagger UI endpoint accessible in development and staging environments.
5. THE Local Development Guide SHALL include a docker-compose.yml that starts all required infrastructure dependencies (PostgreSQL, Redis, Kafka, and a local S3-compatible store) with a single command, and a .env.example file documenting all required environment variables without containing any real secret values.
