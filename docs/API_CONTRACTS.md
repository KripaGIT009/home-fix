# HomeFix — JSON Contracts

Every field name and type here was read out of the Java records and controllers, not inferred. Where a producer and a consumer disagree about the same event, both shapes are shown and the mismatch is called out, because several of those disagreements are live defects.

**Companion documents:** [ARCHITECTURE.md](ARCHITECTURE.md) for diagrams, [LOCAL_ACCESS.md](LOCAL_ACCESS.md) for test users and URLs, [../CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md) for the findings audit.

---

## Platform-wide conventions

**Authentication.** The shared `JwtValidationFilter` reads `Authorization: Bearer <jwt>`, takes the user id from `sub` and roles from the `roles` claim, and grants `ROLE_<NAME>` authorities. Tokens are HS384-signed. `CorrelationIdFilter` propagates `X-Correlation-ID` into the logging context.

**Authorization, as actually configured.** The shared `RbacEnforcementFilter` matches `"METHOD /path/**" → [roles]` entries from `homefix.security.rbac.endpoint-roles`. No service defines that map in YAML. Only admin-service and reporting-service populate it, programmatically. Everywhere else the filter finds no match and passes the request through, so those endpoints are **authenticated only, with no role rule configured**, whatever the Javadoc claims. Entries below say so explicitly rather than repeating the intent.

**Roles.** `CUSTOMER`, `SERVICE_PROVIDER`, `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT`.

**Common security config.** Every service disables CSRF, disables HTTP Basic and form login, sets `STATELESS` sessions, and permits `/health/**`, `/actuator/**`, `/metrics`, `/prometheus` plus the `ERROR` and `ASYNC` dispatches.

### Shared error envelope

`ErrorResponseDto` from the shared observability module, returned by every service's `GlobalExceptionHandler` and by the gateway. `@JsonInclude(NON_EMPTY)` drops empty fields, so `details` and a blank `correlationId` disappear.

| Field | Type | Notes |
|-------|------|-------|
| `errorCode` | string | Stable machine code, e.g. `BOOKING_NOT_FOUND` |
| `message` | string | Human readable, deliberately non-revealing |
| `details` | string[] | Empty by default, omitted when empty |
| `correlationId` | string | From the `correlationId` MDC key, or `X-Correlation-ID` at the gateway |
| `timestamp` | instant | Set at construction |

```json
{
  "errorCode": "INVALID_BOOKING_TRANSITION",
  "message": "Illegal booking state transition from PROVIDER_ARRIVED to JOB_COMPLETED",
  "details": [
    "bookingId: b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
    "fromState: PROVIDER_ARRIVED",
    "toState: JOB_COMPLETED"
  ],
  "correlationId": "9f3c1d7a-45b8-4e06-8a21-6c5d3e9b1f84",
  "timestamp": "2026-09-11T09:30:00Z"
}
```

Two exceptions to the envelope. `RbacEnforcementFilter` writes its own body, so an RBAC rejection returns `{"error":"Insufficient role"}` or `{"error":"Authentication required"}`. dispatch-engine has only a controller-local handler, so its bean-validation failures return Spring's default body; invoice-service has no exception handler at all.

---

## REST contracts

### api-gateway

Port 8080. No controllers; its contract is the route table.

| Path prefix | Target service |
|-------------|----------------|
| `/auth/**` | auth-service :8081 |
| `/customers/**` | customer-service :8082 |
| `/providers/**` | provider-service :8083 |
| `/bookings/**` | booking-service :8084 |
| `/catalog/**` | catalog-service :8085 |
| `/pricing/**` | pricing-engine :8086 |
| `/payments/**` | payment-service :8088 |
| `/invoices/**` | invoice-service :8089 |
| `/notifications/**` | notification-service :8090 |
| `/complaints/**` | complaint-service :8091 |
| `/locations/**` | location-service :8092 |
| `/chat/**` | chat-service :8093 |
| `/verifications/**` | verification-service :8094 |
| `/reports/**` | reporting-service :8096 |
| `/reviews/**` | rating-review-service :8097 |
| `/coupons/**` | promotion-service :8098 |
| `/admin/catalog/**` | catalog-service :8085 |
| `/admin/pricing/**` | pricing-engine :8086 |
| `/admin/verifications/**` | verification-service :8094 |
| `/admin/**` catch-all, last | admin-service :8095 |

Routing problems worth knowing. dispatch-engine has no route at all, so its `/admin/dispatch/weights` is captured by the `/admin/**` catch-all and served by admin-service's own handler against a different store. admin-service's `PUT /admin/pricing/{id}` is likewise shadowed by the `/admin/pricing/**` route to pricing-engine. notification-service is routed but declares no controller, so every `/notifications/**` request reaches a service with no handler.

Global filters, in order, each rejection using the shared envelope:

| Order | Filter | Effect |
|-------|--------|--------|
| -100 | `HttpsRedirectGatewayFilter` | 301 when `X-Forwarded-Proto` is not https and enforcement is on |
| -90 | `CorrelationIdGatewayFilter` | Sets or echoes `X-Correlation-ID` |
| -80 | `WafInspectionGatewayFilter` | Regex scan of path, query values, `User-Agent`, `Referer`, `X-Forwarded-For`, `Cookie` → 400 |
| -70 | `JwtIntrospectionGatewayFilter` | Calls `GET /auth/introspect`; 401 unless the path starts with `/auth/register`, `/auth/login`, `/auth/token/refresh`, `/auth/introspect`, `/actuator`, `/health` |
| -60 | `OtpRateLimitGatewayFilter` | On `/auth/register/otp`, over 5 per phone per hour → 429, `Retry-After: 3600` |
| -50 | `RateLimitGatewayFilter` | 60-second window per subject: CUSTOMER 100, provider 60, default 60 → 429, `Retry-After: 60` |

---

### auth-service

Port 8081, rooted at `/auth`. Every endpoint is public by design: registration is how a caller first obtains a token, and introspection is called by the gateway.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/auth/register/otp` | public | Send a one-time code to an E.164 number |
| POST | `/auth/register/verify` | public | Verify the code, create or augment the account, return tokens |
| POST | `/auth/token/refresh` | public | Rotate the refresh token, issue a new access token |
| POST | `/auth/login/social` | public | Validate a Google or Apple identity token |
| POST | `/auth/logout` | public | Revoke a refresh token, idempotent |
| GET | `/auth/introspect` | public | Validate a JWT and return its claims |

**POST /auth/register/otp**

```json
{
  "mobileNumber": "+919000000001",
  "role": "SERVICE_PROVIDER"
}
```

`mobileNumber` is `@NotBlank` and must match `^\+[1-9]\d{7,14}$`. `role` is optional and case-insensitive, defaulting to `CUSTOMER`. Only `CUSTOMER` and `SERVICE_PROVIDER` are accepted; a staff role is refused with `INVALID_ROLE`, as is an unknown value.

Response 202:

```json
{
  "status": "OTP_SENT",
  "expiresInSeconds": 300
}
```

**POST /auth/register/verify**

```json
{
  "mobileNumber": "+919000000001",
  "otp": "483920"
}
```

`otp` is `@NotBlank` and must match `^\d{4,10}$`; the generator emits six digits.

Response 201, and the same body is returned by `/auth/token/refresh` and `/auth/login/social`:

```json
{
  "userId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "roles": ["CUSTOMER", "SERVICE_PROVIDER"],
  "accessToken": "eyJhbGciOiJIUzM4NCJ9.eyJpc3MiOiJob21lZml4LWF1dGgiLCJzdWIiOiJiYzA1NTFhZS1iM2ZiLTQ1NzEtODU4Ny0wMTdmYTM5MDk2YTQiLCJyb2xlcyI6WyJDVVNUT01FUiJdfQ.signature",
  "refreshToken": "22690c5e-221a-4d50-8d87-dc141e63c9910761c67c-e4a3-4956-b946-7fbe11f9f383",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

`tokenType` is always `Bearer`. The access token TTL defaults to 15 minutes. The refresh token is opaque, two concatenated UUIDs, TTL 30 days.

**POST /auth/token/refresh** and **POST /auth/logout** take the same single `@NotBlank` field:

```json
{ "refreshToken": "22690c5e-221a-4d50-8d87-dc141e63c991..." }
```

Refresh rotates within a token family and re-resolves roles. Replaying an already-rotated token revokes the whole family with `REFRESH_TOKEN_REPLAY`. Logout returns 204 with no body and is idempotent.

**POST /auth/login/social**

```json
{
  "provider": "GOOGLE",
  "identityToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYzEyMyJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20ifQ.signature"
}
```

`provider` is `@NotNull`, one of `GOOGLE` or `APPLE`. First-time social login creates the account with role `CUSTOMER`, linked by the provider and subject pair.

**GET /auth/introspect** takes the token from the `Authorization` header or a `token` query parameter, header winning. It always returns 200; validity is carried by `active`.

```json
{
  "active": true,
  "sub": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "roles": ["CUSTOMER"],
  "iss": "homefix-auth",
  "exp": 1789189800
}
```

An invalid token returns `{"active": false}`. `exp` is epoch seconds, zero when absent; `iss` is an empty string when absent; `roles` is an empty array when absent.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation failed; `details` lists `field: message` |
| 400 | `INVALID_ROLE` | Unknown role, or a staff role a caller may not self-assign |
| 400 | `OTP_EXPIRED_OR_MISSING` | No active session: never requested, or past the 5-minute TTL |
| 400 | `OTP_INCORRECT` | Wrong code; message states remaining attempts |
| 401 | `REFRESH_TOKEN_INVALID` | Blank, unknown, expired, revoked, or the account is gone |
| 401 | `REFRESH_TOKEN_REPLAY` | Already-rotated token presented; family revoked |
| 401 | `SOCIAL_IDENTITY_TOKEN_INVALID` | Provider token signature or audience invalid |
| 401 | `SOCIAL_IDENTITY_TOKEN_EXPIRED` | Provider token expired |
| 401 | `SOCIAL_PROVIDER_UNSUPPORTED` | No verifier registered or configured for that provider |
| 429 | `OTP_SESSION_LOCKED` | 5 wrong attempts; locked 30 minutes, sends `Retry-After` |
| 429 | `OTP_RATE_LIMIT_EXCEEDED` | Over 5 code requests in an hour for that number |
| 502 | `SMS_DELIVERY_FAILED` | Gateway failed; no pending session was created |

---

### customer-service

Port 8082, rooted at `/customers`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| PUT | `/customers/{id}/profile` | authenticated only, no role rule | Update name, email, photo, multipart |
| POST | `/customers/{id}/location/detect` | authenticated only, no role rule | Resolve a service location from device GPS |
| POST | `/customers/{id}/deletion` | authenticated only, no role rule | Request account and data deletion |
| POST | `/customers/{id}/addresses` | authenticated only, no role rule | Add a saved address from coordinates |
| DELETE | `/customers/{id}/addresses/{addressId}` | authenticated only, no role rule | Delete a saved address |

**PUT /customers/{id}/profile** is `multipart/form-data` with a JSON `profile` part and an optional `photo` part. The photo must be `image/jpeg` or `image/png` and at most 5 MB.

```json
{
  "displayName": "Ananya Rao",
  "email": "ananya.rao@example.com",
  "photoUrl": "https://cdn.homefix.example/photos/bc0551ae.jpg"
}
```

`displayName` is `@NotBlank` with max 100 characters, `email` is `@NotBlank` and `@Email`. Both are encrypted before storage and never returned. Note that `photoUrl` is the value actually persisted; the uploaded `photo` part is validated for type and size and then discarded.

Response 200:

```json
{
  "id": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "userId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "photoUrl": "https://cdn.homefix.example/photos/bc0551ae.jpg"
}
```

**POST /customers/{id}/location/detect**

```json
{ "lat": 12.971599, "lng": 77.594566, "gpsDenied": false }
```

`lat` and `lng` are boxed and may be null; either null, or `gpsDenied` true, gives `GPS_UNAVAILABLE`.

```json
{
  "lat": 12.971599,
  "lng": 77.594566,
  "resolvedAddress": "MG Road, Bengaluru, Karnataka 560001",
  "geocoded": true
}
```

The default geocoding adapter is a no-op, so locally `resolvedAddress` is null and `geocoded` is false.

**POST /customers/{id}/deletion** takes no body and returns 202:

```json
{
  "requestId": "8b2d4f60-1c37-4ab9-9e55-7a1c0b3d6e24",
  "status": "ACKNOWLEDGED",
  "acknowledgedAt": "2026-09-11T09:30:00Z",
  "anonymizeBy": "2026-10-11T09:30:00Z"
}
```

**POST /customers/{id}/addresses**

```json
{ "label": "Home", "lat": 12.971599, "lng": 77.594566 }
```

`label` is optional with max 100 characters. `lat` is `@NotNull` in [-90, 90], `lng` `@NotNull` in [-180, 180].

Response 201:

```json
{
  "addressId": "c4e7a1b9-3d28-4f06-9a15-8b6d2e0f7c43",
  "lat": 12.971599,
  "lng": 77.594566,
  "geocoded": false,
  "isDefault": true,
  "warning": "Address could not be resolved automatically; raw coordinates were saved"
}
```

`warning` is null when geocoding succeeded. The first active address becomes the default. The resolved street address is encrypted at rest and never returned.

**DELETE /customers/{id}/addresses/{addressId}** returns 204. Booking-service is asked whether an active booking references the address: a hit gives `ADDRESS_IN_USE`, and an unreachable booking-service gives `BOOKING_SERVICE_UNAVAILABLE` rather than risking a wrong delete. Deleting the default promotes the most recently created remaining address.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation failed |
| 400 | `GPS_UNAVAILABLE` | `gpsDenied` true, or a coordinate missing |
| 400 | `INVALID_PHOTO_TYPE` | Photo part is not JPEG or PNG |
| 400 | `PHOTO_TOO_LARGE` | Photo part over 5 MB |
| 404 | `CUSTOMER_NOT_FOUND` | Address not found for the customer, or no such profile |
| 409 | `ADDRESS_IN_USE` | An active booking references the address |
| 422 | `ADDRESS_LIMIT_REACHED` | 10 active addresses already exist |
| 503 | `BOOKING_SERVICE_UNAVAILABLE` | Booking-service lookup failed |

---

### provider-service

Port 8083, rooted at `/providers/{id}`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/providers/{id}/profile` | authenticated only, no role rule | Read the profile |
| PUT | `/providers/{id}/profile` | authenticated only, no role rule | Replace categories, skills, experience, radius, name |
| PUT | `/providers/{id}/radius` | authenticated only, no role rule | Update the service radius |
| PUT | `/providers/{id}/availability` | authenticated only, no role rule | Replace the weekly schedule |
| PUT | `/providers/{id}/emergency-availability` | authenticated only, no role rule | Toggle emergency availability |
| POST | `/providers/{id}/settlements` | authenticated only, no role rule | Request a wallet settlement |
| GET | `/providers/{id}/earnings?page=&size=` | authenticated only, no role rule | Paginated earnings history |

**PUT /providers/{id}/profile**

```json
{
  "displayName": "Rohit Kumar Electricals",
  "categories": [
    {
      "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
      "subcategoryIds": ["a1c8e4d2-7b63-4f91-8e05-2d7c9a3b6f18"]
    }
  ],
  "skillTags": ["wiring", "switchboard-repair"],
  "yearsExperience": 7,
  "serviceRadiusKm": 25
}
```

`categories` is `@NotNull` with at most 5 entries, each carrying at most 10 subcategory ids. `skillTags` is `@NotNull` with 1 to 20 entries. `yearsExperience` and `serviceRadiusKm` are unannotated primitives; the service enforces 0 to 50 and 1 to 100 km.

Response 200, also returned by the profile read and the three other updates:

```json
{
  "id": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "displayName": "Rohit Kumar Electricals",
  "yearsExperience": 7,
  "serviceRadiusKm": 25,
  "aggregateRating": 4.60,
  "walletBalance": 1250.00,
  "emergencyAvailable": true,
  "underReview": false,
  "bankAccountVerified": true,
  "skillTags": ["wiring", "switchboard-repair"],
  "categories": [
    {
      "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
      "subcategoryIds": ["a1c8e4d2-7b63-4f91-8e05-2d7c9a3b6f18"]
    }
  ],
  "availability": [
    { "dayOfWeek": "MONDAY", "startHour": 9, "endHour": 18 }
  ]
}
```

The encrypted bank reference is never exposed, only `bankAccountVerified`. `underReview` flips when the rating falls below the configured threshold of 3.0.

**PUT /providers/{id}/radius** takes `{"serviceRadiusKm": 40}`. **PUT /providers/{id}/emergency-availability** takes `{"emergencyAvailable": true}` with a `@NotNull` boxed boolean.

**PUT /providers/{id}/availability**

```json
{
  "slots": [
    { "dayOfWeek": "MONDAY", "startHour": 9, "endHour": 13 },
    { "dayOfWeek": "MONDAY", "startHour": 14, "endHour": 18 }
  ]
}
```

Slots are half-open at hour granularity: `startHour` in [0, 23], `endHour` in [1, 24], end strictly after start. An empty list clears the schedule. Overlapping slots on one day give `OVERLAPPING_AVAILABILITY`.

**POST /providers/{id}/settlements**

```json
{ "amount": 1250.00, "bankAccountRef": "HDFC0001234/50100123456789" }
```

`amount` is `@NotNull`, must be at least the 1.00 minimum and at most the wallet balance. Omitting `bankAccountRef` uses the stored verified account; supplying it encrypts the value before persistence. Requires `bankAccountVerified`.

Response 201:

```json
{
  "id": "6d9b1f80-52a7-4c3e-8f14-0b7e9c2a5d63",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1250.00,
  "status": "PENDING",
  "requestedAt": "2026-09-11T09:30:00Z"
}
```

`status` is one of `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`. Note there is no completion path in code, so a settlement stays `PENDING` forever and the debited wallet amount is never released.

**GET /providers/{id}/earnings** returns a Spring Data page. `page` floors at 0, `size` clamps to [1, 100] with a default of 20.

```json
{
  "content": [
    {
      "id": "f0c38a71-6d42-4b95-9e20-3c1a7d5b8e64",
      "bookingId": "9a2e7c41-0b85-4d63-9f17-6e3c8a1d5b20",
      "bookingReference": "HFX-2026-000183",
      "type": "JOB_CREDIT",
      "gross": 1500.00,
      "platformFee": 225.00,
      "net": 1275.00,
      "creditedAt": "2026-09-11T09:30:00Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true,
  "size": 20,
  "number": 0,
  "numberOfElements": 1,
  "empty": false
}
```

`type` is `JOB_CREDIT`, `PLATFORM_FEE_DEDUCTION` or `PENALTY_DEDUCTION`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or the service range checks |
| 400 | `CATEGORY_DEACTIVATED` | Selected category deactivated or absent |
| 400 | `SUBCATEGORY_DEACTIVATED` | Selected subcategory deactivated or absent |
| 400 | `OVERLAPPING_AVAILABILITY` | Two submitted slots overlap on one day |
| 400 | `SETTLEMENT_AMOUNT_TOO_LOW` | Below the 1.00 minimum |
| 400 | `SETTLEMENT_AMOUNT_EXCEEDS_BALANCE` | Over the wallet balance |
| 400 | `NO_VERIFIED_BANK_ACCOUNT` | No verified account on file |
| 404 | `PROVIDER_NOT_FOUND` | No profile for that id |

An encryption failure has no handler and surfaces as a generic 500.

---

### verification-service

Port 8094 locally. Provider paths under `/verifications/{providerId}`, admin paths under `/admin/verifications/{providerId}`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/verifications/{providerId}/documents` | authenticated only, no role rule | Upload required documents, multipart |
| GET | `/verifications/{providerId}` | authenticated only, no role rule | Read the record including its audit trail |
| GET | `/verifications/{providerId}/job-assignment-eligibility` | authenticated only, no role rule | Assert dispatch eligibility, 204 or 403 |
| POST | `/admin/verifications/{providerId}/verify-documents` | authenticated only, no role rule | Mark documents verified, auto-start the check |
| POST | `/admin/verifications/{providerId}/background-check-result` | authenticated only, no role rule | Record the check result |
| POST | `/admin/verifications/{providerId}/approve` | authenticated only, no role rule | Approve, or reinstate a suspended provider |
| POST | `/admin/verifications/{providerId}/reject` | authenticated only, no role rule | Reject with a required reason |
| POST | `/admin/verifications/{providerId}/suspend` | authenticated only, no role rule | Suspend and remove from the dispatch pool |

The admin handler binds a document upload as `@RequestParam Map<String, MultipartFile>`, so **each part name is a document type**: `GOVERNMENT_ID`, `ADDRESS_PROOF`, `SKILL_CERTIFICATION`. All three are required; a missing one gives `MISSING_REQUIRED_DOCUMENTS` listing them, and an unrecognised part name gives `VALIDATION_ERROR`. No per-part size or MIME allow-list is enforced on this path.

Response 201, and the same body with 200 from the status read and all five admin actions:

```json
{
  "id": "5c8e2b17-9f40-4d63-a2b8-7e1c6d9a3f52",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "status": "DOCUMENT_SUBMITTED",
  "backgroundCheckStartedAt": null,
  "backgroundCheckResult": null,
  "documents": [
    {
      "documentType": "GOVERNMENT_ID",
      "storageRef": "s3://homefix-documents/dd73094d/GOVERNMENT_ID/1e7b4d09",
      "uploadedAt": "2026-09-11T09:30:00Z"
    }
  ],
  "auditTrail": [
    {
      "sequence": 1,
      "fromState": "PENDING",
      "toState": "DOCUMENT_SUBMITTED",
      "actorId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
      "reason": "Provider submitted required documents",
      "createdAt": "2026-09-11T09:30:00Z"
    }
  ]
}
```

`status` is one of `PENDING`, `DOCUMENT_SUBMITTED`, `DOCUMENT_VERIFIED`, `BACKGROUND_CHECK_PENDING`, `BACKGROUND_CHECK_COMPLETED`, `APPROVED`, `REJECTED`, `SUSPENDED`. Verifying documents appends two audit entries, because it also initiates the background check. Storage is a stub that discards the bytes while returning an `s3://` reference.

Permitted transitions: `PENDING → DOCUMENT_SUBMITTED`; `DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED | REJECTED`; `DOCUMENT_VERIFIED → BACKGROUND_CHECK_PENDING`; `BACKGROUND_CHECK_PENDING → BACKGROUND_CHECK_COMPLETED`; `BACKGROUND_CHECK_COMPLETED → APPROVED | REJECTED`; `APPROVED → SUSPENDED`; `SUSPENDED → APPROVED`. `REJECTED` is terminal and self-transitions are never permitted.

The four simple admin actions take an optional `{"reason": "..."}` with max 1000 characters, defaulting per action; on reject the reason is required. The background check result takes a required `{"result": "..."}` with max 2000 characters.

Eligibility returns 204 when `APPROVED`, otherwise 403 `PROVIDER_NOT_APPROVED` with `details` carrying `providerId` and `currentState`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | No documents, unknown part name, missing rejection reason |
| 400 | `MISSING_REQUIRED_DOCUMENTS` | Parts do not cover all required types |
| 400 | `DOCUMENT_READ_FAILED` | IO error reading a part |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or the subject is not a UUID |
| 403 | `PROVIDER_NOT_APPROVED` | Eligibility checked while not approved |
| 404 | `VERIFICATION_NOT_FOUND` | No record for that provider |
| 409 | `INVALID_STATE_TRANSITION` | Transition not permitted |

---

### booking-service

Port 8084 locally. `/bookings` for creation and customer actions, `/bookings/{reference}` for job execution. Note that `{reference}` is the human-readable booking reference, not the UUID.

The acting principal is never read from the body: the actor id is the JWT subject and the actor role is the first authority with `ROLE_` stripped, defaulting to `CUSTOMER` in the booking controller and `SERVICE_PROVIDER` in the job execution controller.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/bookings` | authenticated only, no role rule | Create a booking, JSON, returns the estimate |
| POST | `/bookings/media` | authenticated only, no role rule | Same flow, multipart, up to 10 files |
| POST | `/bookings/{reference}/confirmation` | authenticated only, no role rule | Confirm; `CREATED` → `SEARCHING_PROVIDER`, publishes `BookingCreated` |
| POST | `/bookings/{reference}/cancellation` | authenticated only, no role rule | Cancel, applying the fee policy |
| POST | `/bookings/{reference}/on-the-way` | authenticated only, no role rule | → `PROVIDER_ON_THE_WAY` |
| POST | `/bookings/{reference}/arrived` | authenticated only, no role rule | → `PROVIDER_ARRIVED` |
| POST | `/bookings/{reference}/photos` | authenticated only, no role rule | Attach one before or after photo, 204 |
| POST | `/bookings/{reference}/start` | authenticated only, no role rule | → `JOB_STARTED`, requires a before photo |
| POST | `/bookings/{reference}/pause` | authenticated only, no role rule | → `JOB_PAUSED`, reason required |
| POST | `/bookings/{reference}/resume` | authenticated only, no role rule | → `JOB_STARTED` |
| POST | `/bookings/{reference}/parts` | authenticated only, no role rule | Add a parts line item → `CUSTOMER_APPROVAL_PENDING` |
| POST | `/bookings/{reference}/quote/approval` | authenticated only, no role rule | Approve → `JOB_STARTED` |
| POST | `/bookings/{reference}/quote/rejection` | authenticated only, no role rule | Reject → `JOB_COMPLETED` at the original price |
| POST | `/bookings/{reference}/complete` | authenticated only, no role rule | → `JOB_COMPLETED`, requires an after photo |

**POST /bookings**

```json
{
  "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "addressId": "a41d8f62-5c73-4e19-b8a0-62f3d9e1c405",
  "emergency": false,
  "scheduledAt": "2026-09-12T10:00:00Z",
  "description": "Kitchen tap is leaking at the base."
}
```

Category and subcategory are `@NotNull`; `addressId` is optional. When `emergency` is true the booking is driven to `SEARCHING_PROVIDER` in the same request and `scheduledAt` is ignored. Otherwise `scheduledAt` is required, at least 2 hours ahead and at most 90 days out. `description` has a 2000-character cap.

Response 201:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-2026-0004821",
  "status": "CREATED",
  "emergency": false,
  "scheduledAt": "2026-09-12T10:00:00Z",
  "estimatedTotal": 1250.00,
  "cancellationFee": null,
  "estimate": {
    "total": 1250.00,
    "components": {
      "basePrice": 900.00,
      "distanceCharge": 60.00,
      "timeCharge": 0.00,
      "partsMaterialsCharge": 0.00,
      "emergencyCharge": 0.00,
      "weekendSurcharge": 0.00,
      "nightSurcharge": 0.00,
      "demandSurgeCharge": 0.00,
      "platformFee": 144.00,
      "taxes": 146.00,
      "discountAmount": 0.00,
      "couponAmount": 0.00
    }
  }
}
```

`estimate` is populated only on the two creation endpoints; every other response carries `null` there.

**POST /bookings/media** is multipart with text parts `categoryId`, `subcategoryId`, `addressId`, `emergency`, `scheduledAt`, `description` and a repeatable `media` file part. Limits are 10 files per booking, 50 MB each, content type one of `image/jpeg`, `image/png`, `video/mp4`, `video/quicktime`. A malformed `scheduledAt` on this path throws an unhandled parse exception and surfaces as a 500.

**POST /bookings/{reference}/photos** is multipart with a `type` text part that must be exactly `BEFORE_PHOTO` or `AFTER_PHOTO`, and a single `file` part. It returns **204 with no body** and performs no state change.

**POST /bookings/{reference}/cancellation** takes an optional `{"reason": "..."}` with a 500-character cap. The response carries the applied `cancellationFee`. Cancellation is reachable only from `SEARCHING_PROVIDER`, `PROVIDER_ASSIGNED`, `PROVIDER_ACCEPTED` and `PROVIDER_ON_THE_WAY`.

**POST /bookings/{reference}/pause** requires `{"reason": "..."}`, `@NotBlank` with 1 to 500 characters, re-checked after trimming. It closes the open work interval and opens a pause interval.

**POST /bookings/{reference}/parts**

```json
{ "itemName": "Ceramic cartridge 40mm", "quantity": 2, "unitCost": 450.00 }
```

`itemName` is `@NotBlank` max 200, `quantity` at least 1, `unitCost` `@NotNull` at least 0.01. The response always reports `CUSTOMER_APPROVAL_PENDING`, because the handler performs two transitions in one request. The aggregate parts total goes to pricing-engine and the recalculated value is stored as the final total, while `estimatedTotal` keeps the original. An unreachable pricing-engine gives `PRICING_ENGINE_UNAVAILABLE`.

Net duration is the sum of work intervals minus pause intervals, persisted but not exposed in the DTO.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, missing `scheduledAt`, bad photo type |
| 404 | `BOOKING_NOT_FOUND` | No booking for that reference |
| 409 | `INVALID_BOOKING_TRANSITION` | Target state not permitted from the current state |
| 422 | `SERVICE_UNAVAILABLE` | Category or subcategory inactive in the catalog |
| 422 | `LEAD_TIME_TOO_SHORT` | Scheduled sooner than 2 hours out |
| 422 | `SCHEDULING_HORIZON_EXCEEDED` | Scheduled further than 90 days out |
| 422 | `MEDIA_VALIDATION_ERROR` | Over 10 files, empty file, over 50 MB, or a disallowed type |
| 422 | `PHOTO_REQUIRED` | Start without a before photo, complete without an after photo |
| 503 | `PRICING_ENGINE_UNAVAILABLE` | Pricing-engine unreachable |
| 500 | `BOOKING_NOT_COMPLETED` | The booking saga failed and was compensated |

The full transition table is in [ARCHITECTURE.md](ARCHITECTURE.md) section 8.1.

---

### catalog-service

Port 8085. `/catalog` is the public read surface; `/admin/catalog` is CRUD. Only `GET /catalog/**` is permitted anonymously.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/catalog/categories` | public | Active categories with active subcategories, cached |
| GET | `/admin/catalog/categories` | authenticated only, no role rule | List all, including inactive |
| POST | `/admin/catalog/categories` | authenticated only, no role rule | Create a category, 201 |
| PUT | `/admin/catalog/categories/{categoryId}` | authenticated only, no role rule | Update a category |
| POST | `/admin/catalog/categories/{categoryId}/activate` | authenticated only, no role rule | Activate |
| POST | `/admin/catalog/categories/{categoryId}/deactivate` | authenticated only, no role rule | Deactivate |
| DELETE | `/admin/catalog/categories/{categoryId}` | authenticated only, no role rule | Delete with its subcategories, 204 |
| GET | `/admin/catalog/categories/{categoryId}/subcategories` | authenticated only, no role rule | List including inactive |
| POST | `/admin/catalog/categories/{categoryId}/subcategories` | authenticated only, no role rule | Create under an active parent, 201 |
| PUT | `/admin/catalog/subcategories/{subcategoryId}` | authenticated only, no role rule | Update |
| POST | `/admin/catalog/subcategories/{subcategoryId}/activate` | authenticated only, no role rule | Activate |
| POST | `/admin/catalog/subcategories/{subcategoryId}/deactivate` | authenticated only, no role rule | Deactivate |
| DELETE | `/admin/catalog/subcategories/{subcategoryId}` | authenticated only, no role rule | Delete, 204 |

**GET /catalog/categories** omits deactivated entries entirely and deliberately does not expose the `active` flag. Data is at most 300 seconds stale.

```json
[
  {
    "id": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
    "name": "Plumbing",
    "description": "Taps, pipes, drains and water heaters",
    "iconUrl": "https://cdn.homefix.example/icons/plumbing.svg",
    "displayOrder": 1,
    "subcategories": [
      {
        "id": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
        "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
        "name": "Tap repair",
        "basePrice": 349.00,
        "estimatedDurationMin": 45,
        "skillTags": ["plumbing", "tap", "cartridge"],
        "emergencyAvailable": true
      }
    ]
  }
]
```

**Category create and update**

```json
{
  "name": "Plumbing",
  "description": "Taps, pipes, drains and water heaters",
  "iconUrl": "https://cdn.homefix.example/icons/plumbing.svg",
  "displayOrder": 1
}
```

`name` is `@NotBlank` max 120, `description` max 1000, `iconUrl` max 512, `displayOrder` an unconstrained int. The admin response adds `"active": true`.

**Subcategory create and update**

```json
{
  "name": "Tap repair",
  "basePrice": 349.00,
  "estimatedDurationMin": 45,
  "skillTags": ["plumbing", "tap", "cartridge"],
  "emergencyAvailable": true
}
```

`basePrice` must be between 0.01 and 999999.99, `estimatedDurationMin` between 1 and 480, `skillTags` at most 20 entries and null becomes an empty list. The parent comes from the path and must exist and be active.

Category deletion is blocked with 409 when active providers or bookings reference it:

```json
{
  "errorCode": "CATEGORY_HAS_ACTIVE_DEPENDENCIES",
  "message": "Cannot delete category 3f1a6c2e: it has 2 active provider(s) and 1 active booking(s)",
  "details": ["activeProviderCount=2", "activeBookingCount=1", "booking=HFX-2026-0004821"],
  "correlationId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "timestamp": "2026-09-11T09:30:00Z"
}
```

That blocker check is a stub that always reports no dependencies, so categories still in use are hard-deleted. Subcategory deletion has no blocker check at all.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation or a service range check |
| 400 | `PARENT_CATEGORY_NOT_FOUND` | Subcategory create under an unknown parent |
| 400 | `PARENT_CATEGORY_DEACTIVATED` | Subcategory create under a deactivated parent |
| 404 | `CATEGORY_NOT_FOUND` / `SUBCATEGORY_NOT_FOUND` | Unknown id |
| 409 | `CATEGORY_HAS_ACTIVE_DEPENDENCIES` | Delete blocked by dependencies |

---

### pricing-engine

Port 8086. `/pricing` for quotes and override validation, `/admin/pricing` for parameters.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/pricing/estimate` | authenticated only, no role rule | Itemised breakdown for a subcategory |
| POST | `/pricing/overrides` | authenticated only, no role rule | Validate a provider price against floor and ceiling |
| GET | `/admin/pricing/parameters/{subcategoryId}` | authenticated only, no role rule | Read parameters, cached |
| PUT | `/admin/pricing/parameters` | authenticated only, no role rule | Upsert parameters and invalidate the cache |

**POST /pricing/estimate**

```json
{
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "emergency": false,
  "surgeActive": false,
  "distanceKm": 8.50,
  "timeCharge": 120.00,
  "partsMaterialsCharge": 900.00,
  "scheduledLocalTime": "2026-09-12T10:00:00",
  "couponCode": "SAVE20",
  "userId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "orderDiscount": 50.00
}
```

Only `subcategoryId` is `@NotNull`. The numeric fields are optional and null is treated as zero. Note that `scheduledLocalTime` is a `LocalDateTime` with **no offset or trailing Z**; it is evaluated in the configured zone to decide the night window of 22:00 to 06:00 and the weekend window. A non-blank unknown coupon gives `COUPON_NOT_FOUND`.

Response 200. Every component is non-null, the components sum to the total, the total is floored at 0.01, and everything is scale 2 with half-up rounding.

```json
{
  "basePrice": 349.00,
  "distanceCharge": 85.00,
  "timeCharge": 120.00,
  "partsMaterialsCharge": 900.00,
  "emergencyCharge": 0.00,
  "weekendSurcharge": 0.00,
  "nightSurcharge": 0.00,
  "demandSurgeCharge": 0.00,
  "platformFee": 218.10,
  "taxes": 221.17,
  "discountAmount": -50.00,
  "couponAmount": -168.55,
  "total": 1674.72
}
```

Coupon validation runs against the pre-coupon total, so a minimum-order check sees the amount before the coupon is applied.

**POST /pricing/overrides** takes `{"subcategoryId": "...", "proposedPrice": 1250.00}` and, on acceptance, **echoes the request unchanged**; there is no separate response type. A rejection carries the permitted range:

```json
{
  "errorCode": "OVERRIDE_OUT_OF_RANGE",
  "message": "Provider-specific price 1250.00 is outside the permitted range [200.00, 900.00]",
  "details": ["floor=200.00", "ceiling=900.00", "proposed=1250.00"],
  "correlationId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "timestamp": "2026-09-11T09:30:00Z"
}
```

**PUT /admin/pricing/parameters**, also the response body of both admin endpoints:

```json
{
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "basePrice": 349.00,
  "perKmRate": 10.00,
  "maxTravelCharge": 250.00,
  "nightSurcharge": 75.00,
  "weekendSurcharge": 50.00,
  "platformFeeRate": 0.15,
  "taxRate": 0.18,
  "emergencyMultiplier": 1.50,
  "surgeMultiplier": 1.25,
  "overrideFloor": 200.00,
  "overrideCeiling": 900.00
}
```

Only `subcategoryId` and `basePrice` are `@NotNull`. Fee and tax rates are fractions. The emergency and surge multipliers are capped at calculation time by the configured maxima of 2.0.

Parameters and coupons live in in-memory adapters only. They vanish on restart, diverge between replicas, and coupons can never be inserted, so every coupon lookup fails. After a restart, every estimate returns 404 until `docker/seed-pricing.sh` is re-run.

---

### payment-service

Port 8088, controller at `/payments`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/payments` | authenticated only, no role rule | Initiate a payment, idempotent per customer and booking |
| GET | `/payments/{transactionId}` | authenticated only, no role rule | Read a transaction |
| POST | `/payments/callbacks/{transactionId}` | **public**, HMAC verified in the handler | Gateway webhook |
| POST | `/payments/{transactionId}/retries` | authenticated only, no role rule | Record a customer-driven retry |
| POST | `/payments/{transactionId}/refunds` | authenticated only, no role rule | Refund fully or partially |
| POST | `/payments/settlements` | authenticated only, no role rule | Initiate a settlement transfer |

**Idempotency is server-derived, not client-supplied.** There is no `Idempotency-Key` header anywhere in this service. The key is built internally as the literal string `cust:<customerId>:booking:<bookingId>` and held in Redis. A client cannot supply, override or scope it, so replaying the same customer and booking pair returns the original transaction regardless of any header sent.

**POST /payments**

```json
{
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1250.00,
  "platformFee": 187.50,
  "method": "UPI",
  "gatewayId": "razorpay",
  "paymentCredential": "customer@okhdfcbank"
}
```

The three ids, `method` and `gatewayId` are `@NotNull`; `amount` is `@NotNull` and at least 0.01. `platformFee` is optional but must not be negative or exceed the amount. `paymentCredential` is optional and stored encrypted, never returned. `method` is one of `UPI`, `CREDIT_DEBIT_CARD`, `NET_BANKING`, `WALLET`, `CASH`.

Response 201, and the same body with 200 from the read, callback, retry and refund endpoints:

```json
{
  "id": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1250.00,
  "platformFee": 187.50,
  "providerNetEarning": 1062.50,
  "refundedAmount": 0.00,
  "method": "UPI",
  "gateway": "razorpay",
  "status": "PENDING",
  "attemptCount": 1,
  "createdAt": "2026-09-11T09:30:00Z",
  "updatedAt": "2026-09-11T09:30:00Z"
}
```

`providerNetEarning` is derived, not stored. Transitions are `PENDING → SUCCESS | FAILED`, then `SUCCESS → REFUNDED | PARTIALLY_REFUNDED`; `FAILED`, `REFUNDED` and `PARTIALLY_REFUNDED` are terminal.

**POST /payments/callbacks/{transactionId}**

```json
{
  "gatewayId": "razorpay",
  "payload": "{\"order_id\":\"order_NqJ8xT2bVcL9Ae\",\"status\":\"captured\",\"amount\":125000}",
  "signature": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "succeeded": true,
  "failureReason": null
}
```

What the signature actually covers matters, so here it is explicitly. The HMAC is recomputed over the UTF-8 bytes of the `payload` string alone and compared in constant time.

| Field | Signed | How it is used |
|-------|--------|----------------|
| `payload` | **yes**, the only signed input | Verified, then never parsed or inspected |
| `gatewayId` | no | Selects which signing secret verifies the payload |
| `succeeded` | no | Decides `SUCCESS` versus `FAILED`, the entire outcome |
| `failureReason` | no | Recorded on the transaction |
| `{transactionId}` in the path | no | Selects which transaction is mutated |

Verification happens before any state change, so a bad signature gives `INVALID_CALLBACK_SIGNATURE` and logs a security warning. But the signed payload is never cross-checked against the outcome fields or the path id, and the endpoint is public, so a single captured gateway id, payload and signature triple can be retargeted at any other pending transaction with the outcome flipped. That is the critical finding in review section 8.4.

On success the handler publishes `PaymentCompleted` through the outbox in the same transaction, then triggers invoice generation and credits the provider wallet, both with bounded retries and an ops alert on exhaustion.

**POST /payments/{transactionId}/retries** takes **no body**; the optional reason is a query parameter:

```
POST /payments/7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64/retries?failureReason=UPI%20collect%20timed%20out
```

Only a `PENDING` transaction is retryable; anything else gives `NOT_RETRYABLE`. Once the attempt count reaches the configured maximum the transaction is moved to `FAILED` in the same call. Note that this endpoint only increments a counter; it never re-charges the gateway.

**POST /payments/{transactionId}/refunds** takes `{"amount": 500.00}`, `@NotNull` and at least 0.01, legal only from `SUCCESS`, yielding `PARTIALLY_REFUNDED` or `REFUNDED`.

**POST /payments/settlements**

```json
{
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1062.50,
  "bankAccountRef": "HDFC0001234/50100123456789",
  "gatewayId": "razorpay"
}
```

All four are `@NotNull`. Response 201 returns the settlement id, provider, amount, status and timestamps, and never echoes the encrypted bank reference. Settlement statuses are `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, with transitions `PENDING → PROCESSING | FAILED` and `PROCESSING → COMPLETED | FAILED`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or the service money checks |
| 400 | `INVALID_CALLBACK_SIGNATURE` | HMAC over the payload did not verify; rejected before any state change |
| 404 | `TRANSACTION_NOT_FOUND` | Unknown transaction |
| 409 | `INVALID_STATE_TRANSITION` | Refund or status change not permitted from the current status |
| 409 | `NOT_RETRYABLE` | Retry attempted on a non-pending transaction |
| 502 | `REFUND_GATEWAY_ERROR` | The gateway refund call threw or returned a failure |

---

### invoice-service

Port 8089, read-only controller at `/invoices`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/invoices/customers/{customerId}?page=&size=` | authenticated only, no role rule | Invoice history within the 24-month retention window |
| GET | `/invoices/providers/{providerId}/statements/{year}/{month}` | authenticated only, no role rule | Monthly earnings statement |

`page` defaults to 0 and `size` to 20, both unclamped plain ints. Year and month are plain int path variables with no range validation.

**This service has no exception handling at all**: no advice, no handler, nothing. Any failure surfaces as Spring Boot's default error body rather than the shared envelope, so there is no error-code table to document.

```json
[
  {
    "id": "4a7c2e90-6b13-4d85-9f02-8e5a1c3d7b46",
    "invoiceNumber": "HFX-INV-2026-000912",
    "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
    "paymentId": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",
    "generatedAt": "2026-09-11T09:30:00Z",
    "downloadUrl": "https://homefix-invoices.s3.amazonaws.com/HFX-INV-2026-000912.pdf?X-Amz-Expires=900",
    "downloadUrlExpiresAt": "2026-09-11T09:45:00Z"
  }
]
```

The download URL is minted per request and is not persisted on the invoice row. An empty history returns 200 with an empty array. The PDF itself is never served by this controller: it is rendered when `PaymentCompleted` arrives, handed to the storage port, and reachable only through that signed URL. The only storage adapter is in-memory, so the bytes vanish on restart while the database rows survive.

```json
{
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "year": 2026,
  "month": 8,
  "jobCount": 23,
  "grossEarnings": 34500.00,
  "platformFees": 5175.00,
  "netPayout": 29325.00,
  "available": false
}
```

`available` stays false until the first day of the following month. The statement returns 200 either way; unavailability is a flag, not a status code.

---

### promotion-service

Port 8098 locally, single controller at `/coupons`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/coupons` | authenticated only, no role rule | Create a coupon |
| GET | `/coupons/{id}` | authenticated only, no role rule | Read by id |
| GET | `/coupons/code/{code}` | authenticated only, no role rule | Read by code, case-insensitive |
| POST | `/coupons/{id}/activate` | authenticated only, no role rule | Re-enable |
| POST | `/coupons/{id}/deactivate` | authenticated only, no role rule | Stop further redemptions |
| POST | `/coupons/validate` | authenticated only, no role rule | Checkout validation; moves no counters |
| POST | `/coupons/redeem` | authenticated only, no role rule | Atomically advance both counters |
| POST | `/coupons/cancel` | authenticated only, no role rule | Atomically reverse both counters |

**POST /coupons**

```json
{
  "code": "SAVE20",
  "discountType": "PERCENTAGE",
  "discountValue": 20.00,
  "minOrderValue": 500.00,
  "maxDiscountCap": 300.00,
  "validFrom": "2026-09-01",
  "expiryDate": "2026-12-31",
  "perUserLimit": 2,
  "totalLimit": 1000
}
```

`code` must match `^[A-Za-z0-9]{4,20}$` and is normalised to upper case and unique case-insensitively. `discountType` is `FLAT` or `PERCENTAGE`, and `maxDiscountCap` is mandatory for `PERCENTAGE`, enforced in the domain rather than by bean validation. `discountValue` must be positive and `minOrderValue` non-negative. The two dates are `LocalDate`, with expiry strictly after the start. Both limits are at least 1.

Response 201, and the body of every endpoint here except validate:

```json
{
  "id": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "code": "SAVE20",
  "discountType": "PERCENTAGE",
  "discountValue": 20.00,
  "minOrderValue": 500.00,
  "maxDiscountCap": 300.00,
  "validFrom": "2026-09-01",
  "expiryDate": "2026-12-31",
  "perUserLimit": 2,
  "totalLimit": 1000,
  "totalUsed": 0,
  "active": true
}
```

**POST /coupons/validate** takes the code, a required `userId` and a non-negative `orderValue`, and returns only the computed discount:

```json
{
  "couponId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "code": "SAVE20",
  "discountAmount": 250.00
}
```

This is strictly read-only and safe to repeat. Constraints are evaluated in a fixed order and the first violation is thrown: active status, then start date, then expiry, then minimum order value, then per-user limit, then total limit.

**POST /coupons/redeem** and **POST /coupons/cancel** share the same body, just the code and `userId`, with no order value since the discount was already settled at validation. Both move the coupon total and the per-user row in one transaction, retried on optimistic-lock contention up to five times, after which they give `CONCURRENT_MODIFICATION`, which is safe to retry.

Redemption re-checks the limits at commit time, so it can still fail with an inactive coupon or a limit breach even after a successful validation. Clients must handle that race rather than trusting the earlier call.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or a domain rule such as a missing cap on a percentage coupon |
| 404 | `COUPON_NOT_FOUND` | Unknown id or code, or no usage row to reverse on cancel |
| 409 | `DUPLICATE_COUPON_CODE` | The code already exists, case-insensitively |
| 409 | `CONCURRENT_MODIFICATION` | Five optimistic-lock retries exhausted; retry is safe |
| 422 | `COUPON_INACTIVE` | Coupon deactivated |
| 422 | `COUPON_NOT_STARTED` | Before the start date |
| 422 | `COUPON_EXPIRED` | After the expiry date |
| 422 | `MIN_ORDER_VALUE_NOT_MET` | Order below the coupon minimum |
| 422 | `PER_USER_LIMIT_REACHED` | This user has used it up |
| 422 | `TOTAL_LIMIT_REACHED` | Global limit reached |

Those last five codes differ from the ones pricing-engine returns for the same constraints, so a client calling both must accept either spelling. Pricing-engine says `COUPON_NOT_YET_VALID`, `COUPON_MIN_ORDER_NOT_MET`, `COUPON_PER_USER_LIMIT_REACHED` and `COUPON_TOTAL_LIMIT_REACHED`.

---

### complaint-service

Port 8091, controller at `/complaints`. The `customerId` is always the JWT subject, never the body.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/complaints` | authenticated only, no role rule | Raise a complaint against a booking |
| POST | `/complaints/{complaintId}/status` | authenticated only, no role rule | Change status and notify the customer |
| POST | `/complaints/{complaintId}/refund` | authenticated only, no role rule | Approve a refund, 202 with an empty body |
| POST | `/complaints/{complaintId}/dispute` | authenticated only, no role rule | Set disputed and hold the provider settlement |
| GET | `/complaints/stats` | authenticated only, no role rule | Aggregated statistics |

The Javadoc claims the four staff operations are role-gated, but no rule is registered, so any authenticated caller reaches them.

**POST /complaints**

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "category": "POOR_QUALITY",
  "priority": "STANDARD",
  "description": "The repaired tap started leaking again within two hours.",
  "attachments": [
    { "fileName": "leak-after-repair.jpg", "sizeBytes": 482931 }
  ]
}
```

Booking, category and priority are `@NotNull`; `providerId` is optional; `description` is required with a 2000-character cap. Attachments carry **metadata only, no bytes**: this is a JSON endpoint, not multipart. Size and count limits are enforced in the service rather than by bean validation. Priority is `EMERGENCY` or `STANDARD` and drives the SLA deadline.

Response 201, and the same body with 200 from the status and dispute endpoints:

```json
{
  "id": "e81c4f27-3a69-4b05-9d82-7f1e6c3a5b94",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "agentId": "3a351d47-7395-4902-85d5-3c354dcb2ee7",
  "category": "POOR_QUALITY",
  "priority": "STANDARD",
  "status": "OPEN",
  "acknowledged": true,
  "settlementHeld": false,
  "escalated": false,
  "createdAt": "2026-09-11T09:30:00Z",
  "slaDeadline": "2026-09-13T09:30:00Z",
  "resolvedAt": null
}
```

An agent is assigned at creation from a directory stub that returns random UUIDs. `resolvedAt` stays null until a terminal status, and `RESOLVED` or `CLOSED` release any settlement hold.

The status change takes `{"status": "IN_PROGRESS"}`. The refund takes `{"amount": 1250.00}`, positive, and returns **202 with an empty body** because the refund is coordinated asynchronously; a failure moves the complaint to `REFUND_FAILED` rather than failing the call. The dispute endpoint takes no body and sets the settlement hold.

**GET /complaints/stats**

```json
{
  "categoryCounts": {
    "POOR_QUALITY": 42,
    "LATE_ARRIVAL": 17,
    "OVERCHARGING": 8
  },
  "totalComplaints": 95,
  "resolvedComplaints": 78,
  "resolutionRate": 0.8210526315789474,
  "averageResolutionSeconds": 154800
}
```

`resolutionRate` is a raw double fraction, not a percentage. This endpoint loads every complaint into memory on each call and on every scheduled tick.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or an attachment size or count violation |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 404 | `COMPLAINT_NOT_FOUND` | Unknown complaint |
| 409 | `INVALID_COMPLAINT_TRANSITION` | Status not reachable from the current one |
| 503 | `NO_AGENT_AVAILABLE` | No support agent could be assigned |

The refund path has no status precondition and records nothing on success, so repeated calls issue repeated refunds. See review section 8.5.

---

### location-service

Port 8092 locally, controller at `/locations`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/locations/{bookingId}` | authenticated only, no role rule | Ingest a GPS ping, return the recomputed ETA, 202 |
| GET | `/locations/{bookingId}` | authenticated only, no role rule | Current tracking view |
| GET | `/locations/{bookingId}/stream` | authenticated only, no role rule | SSE stream of live pushes |

```json
{
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "latitude": 12.971599,
  "longitude": 77.594566
}
```

All three are `@NotNull`, with latitude in [-90, 90] and longitude in [-180, 180], re-checked in the domain type. Note that `providerId` is taken from the **body**, not the token, so a caller can claim to be any provider.

Response 202, and the same shape is pushed over SSE:

```json
{ "latitude": 12.971599, "longitude": 77.594566, "etaMinutes": 8 }
```

Tracking view:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "latitude": 12.971599,
  "longitude": 77.594566,
  "lastUpdatedAt": "2026-09-11T09:30:00Z",
  "stale": false
}
```

The ETA uses a haversine distance at a fixed 30 km/h against a destination resolver that returns (0, 0), so locally every ETA is the distance to the Gulf of Guinea.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation on the update body |
| 404 | `LOCATION_NOT_AVAILABLE` | No cached location for the booking |
| 409 | `LOCATION_BOOKING_TERMINATED` | Booking already terminated; further pings rejected |
| 429 | `LOCATION_UPDATE_RATE_LIMITED` | Faster than one ping per 5 seconds |

---

### chat-service

Port 8093. REST at `/chat/channels/{bookingId}/messages`, STOMP at `/ws/chat` with the broker on `/topic` and fan-out to `/topic/chat/{bookingId}`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/chat/channels/{bookingId}/messages` | authenticated, participant check enforced | Send a message, 201 |
| GET | `/chat/channels/{bookingId}/messages` | authenticated, participant check enforced | Full history, oldest first |

This is the one service that gets authorization right: `ChatService` compares the principal against the channel's stored customer and provider ids and throws `CHANNEL_ACCESS_FORBIDDEN` otherwise, and `WebSocketAuthorizationConfig` applies the same rule to STOMP `SUBSCRIBE`. The gap is that the interceptor only inspects `SUBSCRIBE`, so a connected client can `SEND` straight to `/topic/chat/{bookingId}` and bypass the participant check, the masking and the persistence.

```json
{ "body": "I'm at the gate, which floor? Call +919000000001 if needed." }
```

`body` is `@NotBlank` with a 4000-character cap, re-checked after trimming.

Response 201, with the body already masked:

```json
{
  "id": "0b6d2f84-7e19-4c53-a8f0-5d3c1b9e7a26",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "senderId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "body": "I'm at the gate, which floor? Call +91******0001 if needed.",
  "sentAt": "2026-09-11T09:30:00Z"
}
```

Phone numbers are masked before both storage and delivery. The history endpoint returns an array of that object and is unpaginated.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Blank body after trimming |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or the subject is not a UUID |
| 403 | `CHANNEL_ACCESS_FORBIDDEN` | Caller is neither the customer nor the provider |
| 404 | `CHANNEL_NOT_FOUND` | No channel for that booking |
| 409 | `CHANNEL_DEACTIVATED` | Channel already closed by payment or cancellation |

---

### rating-review-service

Port 8097 locally, controller at `/reviews`. The reviewer is the JWT subject. The client IP for fraud detection is the first hop of `X-Forwarded-For`, which a client can set freely.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/reviews` | authenticated only, no role rule | Customer reviews provider |
| POST | `/reviews/customer` | authenticated only, no role rule | Provider reviews customer |
| POST | `/reviews/{reviewId}/approval` | authenticated only, no role rule | Approve a flagged review back into the aggregate |
| POST | `/reviews/{reviewId}/removal` | authenticated only, no role rule | Remove a review and recalculate, 204 |

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "overall": 5,
  "behavior": 5,
  "quality": 4,
  "timeliness": 5,
  "pricingTransparency": 4,
  "reviewText": "Arrived on time, explained the fix, cleaned up afterwards.",
  "attachments": [{ "fileName": "finished-tap.jpg", "sizeBytes": 392184 }]
}
```

All five scores are primitives constrained to 1 through 5. `reviewText` caps at 1000 characters and `attachments` at 5 entries.

Response 201:

```json
{
  "reviewId": "d72a9e18-4c05-4b63-8f29-1e6d3a7c5b40",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "revieweeId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "reviewerRole": "CUSTOMER",
  "overall": 5,
  "flagged": false,
  "submittedAt": "2026-09-11T09:30:00Z"
}
```

A flagged review is excluded from the aggregate until approved. The four sub-scores and the text are not echoed back. Removal requires `{"reason": "..."}`, `@NotBlank` with a 1000-character cap, and returns 204.

The submission path persists the review prompt's reviewer id rather than the caller's, so any authenticated user can post a review on any booking while impersonating the real customer.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or a blank removal reason |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 404 | `REVIEW_NOT_FOUND` | Unknown review, or no open prompt for the booking |
| 409 | `REVIEW_WINDOW_CLOSED` | The 7-day window has closed |
| 409 | `DUPLICATE_REVIEW` | A review already exists for that booking and role |

---

### dispatch-engine

Port 8087, single controller at `/admin/dispatch/weights`. **No gateway route points here**; the `/admin/**` catch-all sends this path to admin-service instead.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/admin/dispatch/weights` | authenticated only, no role rule | Read the matching weights |
| PUT | `/admin/dispatch/weights` | authenticated only, no role rule | Replace them, validated before the store changes |

```json
{
  "distanceWeight": 0.30,
  "availabilityWeight": 0.25,
  "ratingWeight": 0.20,
  "skillWeight": 0.15,
  "performanceWeight": 0.10
}
```

All five are `@NotNull` and must sum to exactly 1.0, checked with `BigDecimal` before the store is touched, so a rejected update leaves the existing weights intact. The same shape is the response. Weights are held in a process-local map, so changes are lost on restart and differ between replicas.

This service has no `@ControllerAdvice`, only a local handler for weight validation, so a `@NotNull` violation returns Spring's default error body rather than the shared envelope. The weight-validation response is also missing a `correlationId`.

---

### admin-service

Port 8095, everything under `/admin`. One of only two services with real role enforcement, and it has two layers.

`AdminRbacConfig` fills the shared rule map, most specific first: every method on `/admin/system-config/**` requires `SUPER_ADMIN`, and every method on `/admin/**` requires `ADMIN` or `SUPER_ADMIN`. Then `AdminAuthorization` re-checks per module: `SUPER_ADMIN` passes everything, `ADMIN` passes everything except modules flagged super-admin-only, of which `SYSTEM_CONFIGURATION` is the only one. A denial gives 403 `MODULE_ACCESS_DENIED` in the shared envelope, whereas a filter rejection gives the filter's bare `{"error": "..."}` body.

The fifteen modules are `USER_MANAGEMENT`, `PROVIDER_MANAGEMENT`, `VERIFICATION_QUEUE`, `SERVICE_CATEGORY_MANAGEMENT`, `PRICING_CONFIGURATION`, `DISPATCH_RULE_CONFIGURATION`, `BOOKING_MANAGEMENT`, `PAYMENT_AND_REFUND_MANAGEMENT`, `COMPLAINT_MANAGEMENT`, `REVIEW_MODERATION`, `COUPON_MANAGEMENT`, `NOTIFICATION_TEMPLATES`, `REPORT_GENERATION`, `AUDIT_LOGS` and `SYSTEM_CONFIGURATION`.

Key endpoints:

| Method | Path | Module | Purpose |
|--------|------|--------|---------|
| GET | `/admin/dashboard` | none checked | Live operational metrics |
| GET | `/admin/audit-logs?entityType=&entityId=` | `AUDIT_LOGS` | Trail for one entity |
| GET | `/admin/audit-logs?actorId=` | `AUDIT_LOGS` | Trail for one actor |
| GET, PUT | `/admin/dispatch/weights` | `DISPATCH_RULE_CONFIGURATION` | Read and update weights, audited |
| GET | `/admin/system-config` | `SYSTEM_CONFIGURATION` | All config keys, super admin only |
| PUT | `/admin/system-config/{key}` | `SYSTEM_CONFIGURATION` | Set one key, audited, super admin only |
| GET | `/admin/verification-queue` | `VERIFICATION_QUEUE` | Pending provider verifications |
| PUT, DELETE | `/admin/users/{id}` | `USER_MANAGEMENT` | Update, delete |
| PUT, POST | `/admin/providers/{id}`, `/approve`, `/reject` | `PROVIDER_MANAGEMENT` | Manage providers |
| POST, PUT, DELETE | `/admin/categories`, `/{id}` | `SERVICE_CATEGORY_MANAGEMENT` | Manage categories |
| PUT | `/admin/pricing/{id}` | `PRICING_CONFIGURATION` | Update pricing, shadowed by the gateway route |
| PUT | `/admin/bookings/{id}` | `BOOKING_MANAGEMENT` | Update a booking |
| POST | `/admin/payments/{id}/refund` | `PAYMENT_AND_REFUND_MANAGEMENT` | Approve a refund |
| PUT | `/admin/complaints/{id}` | `COMPLAINT_MANAGEMENT` | Update a complaint |
| DELETE, POST | `/admin/reviews/{id}`, `/approve` | `REVIEW_MODERATION` | Moderate reviews |
| POST, DELETE | `/admin/coupons`, `/{id}` | `COUPON_MANAGEMENT` | Manage coupons |
| PUT | `/admin/notification-templates/{id}` | `NOTIFICATION_TEMPLATES` | Update a template |
| POST | `/admin/reports` | `REPORT_GENERATION` | Generate a report |

**GET /admin/dashboard**

```json
{
  "activeBookings": 128,
  "activeProvidersOnline": 342,
  "newRegistrationsLast24h": 57,
  "grossRevenueLast24h": 184250.00,
  "avgProviderResponseTimeSeconds": 42.7,
  "openComplaintCount": 17,
  "platformRating": 4.43,
  "refreshedAt": "2026-09-11T09:30:00Z"
}
```

**GET /admin/audit-logs** returns the JPA entity directly, with the before and after states as **JSON-encoded strings**, not nested objects:

```json
[
  {
    "id": "a93c5e71-2d48-4b06-8f15-6c7e9a3d1b82",
    "actorId": "339a9dc7-1e33-4acb-8549-a3916ae3bcef",
    "actionType": "UPDATE",
    "entityType": "DISPATCH_WEIGHTS",
    "entityId": "GLOBAL",
    "beforeValues": "{\"distanceWeight\":0.30,\"ratingWeight\":0.20}",
    "afterValues": "{\"distanceWeight\":0.35,\"ratingWeight\":0.20}",
    "loggedAt": "2026-09-11T09:30:00Z"
  }
]
```

`actionType` is `CREATE`, `UPDATE`, `DELETE`, `APPROVE` or `REJECT`. The two audit-log handlers are discriminated by query parameters, so a request with neither matches no handler and returns 404.

**PUT /admin/system-config/{key}** takes a raw map and reads only the `value` entry, returning the whole config map after the write:

```json
{ "value": "900" }
```

The operational module endpoints all take and return an untyped map with no DTO; the body is passed straight to a stub adapter and echoed back, with before and after state captured into the audit log. Dispatch weights and system config are held in memory only, so the audit log records changes that do not survive a restart.

| HTTP | errorCode | When |
|------|-----------|------|
| 403 | `MODULE_ACCESS_DENIED` | An `ADMIN` reaching `SYSTEM_CONFIGURATION` |
| 400 | `INVALID_DISPATCH_WEIGHTS` | A weight out of range, or the five not summing to 1.0 |
| 400 | `VALIDATION_ERROR` | Bean validation on the weights request |
| 401, 403 | bare `{"error":"..."}` | Rejected by the RBAC filter before the controller |

---

### reporting-service

Port 8096, controller at `/reports`. The other service with real role rules: the filter gates all of `/reports/**` to `ADMIN`, `SUPER_ADMIN` or `FINANCE_ADMIN`, and `ReportAuthorization` then requires `FINANCE_ADMIN` specifically for finance-restricted report types.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/reports` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN, plus a per-type check | Generate inline, 200, or accept for async delivery, 202 |
| POST | `/reports/export` | same | Same, returning a CSV or PDF attachment |

The nine report types, with the finance restriction marked:

| Constant | Finance admin required |
|----------|------------------------|
| `DAILY_REVENUE_SUMMARY` | no |
| `WEEKLY_REVENUE_SUMMARY` | no |
| `MONTHLY_REVENUE_SUMMARY` | no |
| `PROVIDER_PERFORMANCE` | no |
| `SERVICE_CATEGORY_DEMAND` | no |
| `CUSTOMER_RETENTION` | no |
| `COMPLAINT_RESOLUTION` | no |
| `PAYMENT_RECONCILIATION` | **yes** |
| `SETTLEMENT` | **yes** |

```json
{
  "reportType": "PROVIDER_PERFORMANCE",
  "from": "2026-08-01",
  "to": "2026-08-31",
  "serviceCategory": "Plumbing",
  "region": "Bengaluru South",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "format": "CSV"
}
```

`reportType`, `from`, `to` and `format` are `@NotNull`. Dates are `LocalDate`, not instants, and `to` must not precede `from`. The requestor is taken from the token, never the body. `format` is `PDF` or `CSV`.

Synchronous response 200, with rows as a matrix of strings:

```json
{
  "mode": "SYNCHRONOUS",
  "accepted": false,
  "reportType": "PROVIDER_PERFORMANCE",
  "columns": ["Provider", "Jobs completed", "Avg rating", "Gross earnings", "Cancellations"],
  "rows": [
    ["Rohit Kumar Electricals", "23", "4.60", "34500.00", "1"],
    ["Sunrise Plumbing Co.", "18", "4.35", "26100.00", "2"]
  ],
  "message": "2 providers matched the supplied filters"
}
```

A large range is routed to asynchronous email delivery and the body collapses to:

```json
{
  "mode": "ASYNCHRONOUS",
  "accepted": true,
  "message": "Report is being generated; a download link will be emailed when ready."
}
```

The export endpoint returns raw bytes with `Content-Type` `text/csv` or `application/pdf` and a `Content-Disposition` filename derived from the report type. When the selector chooses asynchronous it returns 202 with **no body at all**. The PDF writer is hand-rolled, has no xref table, and computes its length in characters rather than bytes; CSV output is not escaped against formula injection.

| HTTP | errorCode | When |
|------|-----------|------|
| 403 | `REPORT_ACCESS_DENIED` | A finance-restricted type without `FINANCE_ADMIN` |
| 400 | `INVALID_REPORT_REQUEST` | `to` before `from`, or a null required filter |
| 400 | `VALIDATION_ERROR` | Bean validation |

---

### notification-service and outbox-processor

Neither declares a controller. notification-service is a pure Kafka consumer, and outbox-processor is the relay; both expose only health, metrics and Prometheus at the root path. Their contracts are events, below. Note that the gateway still routes `/notifications/**` to a service with no handler.

---

## Event contracts

### The outbox envelope

Producers never talk to Kafka. `OutboxEventPublisher.publish(aggregateType, aggregateId, eventType, payload)` is annotated `@Transactional(propagation = MANDATORY)`, so calling it outside a transaction throws by design and the event row always commits with the business state change.

The `outbox_event` row carries `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `status`, `retry_count`, `created_at`, `published_at`, `last_error` and a `@Version` column, indexed on status and creation time.

The relay then sends:

| Element | Value |
|---------|-------|
| Topic | From the mapping below, else `domain-events` |
| Key | `aggregate_id` as a string, so all events for one aggregate keep order on one partition |
| Headers | `eventId` only, the outbox row id. There is no `eventType` or `aggregateType` header |
| Value | The `payload` column verbatim, with no wrapping envelope |

The producer refuses to start unless `acks=all` and idempotence are enabled. The same `eventId` is reused across retries, never regenerated. Polling is 100 rows per second, with backoff from 1 second doubling to a 60-second cap and 10 attempts before the row is marked `FAILED` and ops are alerted.

**Consumer-side dedupe.** `IdempotentKafkaConsumer` extracts `eventId`, dead-letters immediately if it is absent, skips if the `(consumerGroup, eventId)` row already exists, otherwise handles the record with up to 3 attempts 5 seconds apart and then records the marker. The marker is written in a separate transaction from the handler, so exactly-once is not guaranteed.

**Dead letters** go to `<sourceTopic>.DLT` with headers `eventId`, `originalTopic` and `dlqReason`, preserving the key.

### Topic map

| Event type | Topic |
|------------|-------|
| `BookingCreated` | `BookingCreated` |
| `ProviderAssigned` | `ProviderAssigned` |
| `ProviderAccepted` | `ProviderAccepted` |
| `ProviderRejected` | `ProviderRejected` |
| `ProviderArriving` | `ProviderArriving` |
| `ProviderArrived` | `ProviderArrived` |
| `JobStarted` | `JobStarted` |
| `JobCompleted` | `JobCompleted` |
| `PaymentCompleted` | `PaymentCompleted` |
| `BookingCancelled` | `BookingCancelled` |
| `ReviewSubmitted` | `ReviewSubmitted` |
| anything unmapped | `domain-events` |

`ComplaintCreated` and `ComplaintStatusChanged` are **not** in the mapping, so both land on `domain-events`, where nothing subscribes.

### Event catalogue

| Event | Topic | Producer | Consumers |
|-------|-------|----------|-----------|
| `BookingCreated` | `BookingCreated` | booking-service, on confirmation | dispatch-engine, notification-service |
| `ProviderArriving` | `ProviderArriving` | booking-service | notification-service |
| `ProviderArrived` | `ProviderArrived` | booking-service | notification-service |
| `JobStarted` | `JobStarted` | booking-service | notification-service. location-service listens on `booking.job-started` instead |
| `JobCompleted` | `JobCompleted` | booking-service | notification-service |
| `ProviderAccepted` | `ProviderAccepted` | dispatch-engine | chat-service, notification-service |
| `PaymentCompleted` | `PaymentCompleted` | payment-service | invoice-service, rating-review-service, chat-service, notification-service |
| `ReviewSubmitted` | `ReviewSubmitted` | rating-review-service | notification-service |
| `ProviderAssigned` | `ProviderAssigned` | **no producer** | notification-service |
| `ProviderRejected` | `ProviderRejected` | **no producer** | notification-service |
| `BookingCancelled` | `BookingCancelled` | **no producer** | chat-service, notification-service |
| `ComplaintCreated` | `domain-events` | complaint-service | **none** |
| `ComplaintStatusChanged` | `domain-events` | complaint-service | **none** |

Consumer records are all annotated to ignore unknown properties, so extra producer fields are tolerated. Missing fields deserialise to null or zero and then fail the consumer's own checks, which is where the mismatches below bite.

#### BookingCreated

Producer:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-2026-0004821",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "addressId": "a41d8f62-5c73-4e19-b8a0-62f3d9e1c405",
  "emergency": false,
  "scheduledAt": "2026-09-12T10:00:00Z",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

What dispatch-engine's consumer expects:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "customerLat": 12.971599,
  "customerLon": 77.594566,
  "requiredSkillTags": ["plumbing", "tap"],
  "emergency": false
}
```

**Mismatch.** The producer never emits `customerLat`, `customerLon` or `requiredSkillTags`. They deserialise to 0.0, 0.0 and null, so provider matching scores every candidate against the null island with no skill filter. The producer sends `addressId` where the consumer wants coordinates. Fixing this means resolving the address to coordinates and the subcategory to skill tags before publishing.

#### ProviderArriving and ProviderArrived

Identical shape:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-2026-0004821",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

**Mismatch.** No `customerId` and no `recipientUserId`, so notification-service cannot resolve a recipient, throws, retries three times and dead-letters. No notification is ever delivered for either event.

#### JobStarted

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-2026-0004821",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "startedAt": "2026-09-11T09:30:00Z",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

**Mismatch, topic rather than schema.** location-service's consumer hard-codes the topic `booking.job-started`, which is not configurable and does not match the published `JobStarted`. The payload shape itself is compatible, since the consumer reads only `bookingId`. The effect is that tracking sessions are never terminated on job start. notification-service also dead-letters this event for lack of a recipient.

#### JobCompleted

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-2026-0004821",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "completedAt": "2026-09-11T10:45:00Z",
  "netDurationSeconds": 4200,
  "finalTotal": 1674.72,
  "occurredAt": "2026-09-11T10:45:00Z"
}
```

Dead-letters at notification-service for the same missing-recipient reason.

#### ProviderAccepted

Producer:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "acceptedAt": "2026-09-11T09:30:00Z"
}
```

What chat-service expects in order to activate a channel:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "bookingCreatedAt": "2026-09-11T09:25:00Z"
}
```

**Mismatch.** The producer omits `customerId`, which the activation branch explicitly requires, so every `ProviderAccepted` is retried and dead-lettered and **chat channels are never activated**. The missing `bookingCreatedAt` is tolerated via a clock fallback. The `acceptedAt` the producer does send is never read.

#### PaymentCompleted

```json
{
  "paymentId": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1250.00,
  "platformFee": 187.50,
  "providerNetEarning": 1062.50,
  "paymentMethod": "UPI",
  "completedAt": "2026-09-11T09:30:00Z"
}
```

This is the one event with no schema drift: the invoice and rating consumer records are field-for-field identical to the producer. Three copies of the same record exist in three modules, which is a standing duplication risk. The aggregate id is the transaction id rather than the booking id, so these records key into a different partition space from the booking events.

`paymentMethod` is one of `UPI`, `CREDIT_DEBIT_CARD`, `NET_BANKING`, `WALLET`, `CASH`.

#### ReviewSubmitted

```json
{
  "reviewId": "d72a9e18-4c05-4b63-8f29-1e6d3a7c5b40",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reviewerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "revieweeId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "reviewerRole": "CUSTOMER",
  "overallRating": 5,
  "submittedAt": "2026-09-11T09:30:00Z"
}
```

Dead-letters at notification-service: the reviewer and reviewee are named `reviewerId` and `revieweeId`, so no recipient resolves.

#### Complaint events

```json
{
  "complaintId": "e81c4f27-3a69-4b05-9d82-7f1e6c3a5b94",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "agentId": "3a351d47-7395-4902-85d5-3c354dcb2ee7",
  "category": "POOR_QUALITY",
  "createdAt": "2026-09-11T09:30:00Z"
}
```

```json
{
  "complaintId": "e81c4f27-3a69-4b05-9d82-7f1e6c3a5b94",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "previousStatus": "OPEN",
  "newStatus": "IN_PROGRESS",
  "changedAt": "2026-09-11T09:30:00Z"
}
```

Categories are `POOR_QUALITY`, `LATE_ARRIVAL`, `OVERCHARGING`, `UNPROFESSIONAL_BEHAVIOR`, `INCOMPLETE_WORK`, `DAMAGE`, `PAYMENT_ISSUE`. Statuses are `OPEN`, `IN_PROGRESS`, `ESCALATED`, `DISPUTED`, `REFUND_FAILED`, `RESOLVED`, `CLOSED`, with `previousStatus` null on the first transition. Both events go to `domain-events` and are never consumed.

#### The notification-service consumer contract

One consumer group binds all eleven lifecycle topics and deserialises every one into the same record:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "bookingReference": "HFX-2026-0004821",
  "recipientUserId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "mobileNumber": "+919000000001",
  "emailAddress": "ananya.rao@example.com",
  "deviceToken": "fcm-dGhpcyBpcyBhIGRldmljZSB0b2tlbg"
}
```

**No producer emits any of `recipientUserId`, `mobileNumber`, `emailAddress` or `deviceToken`.** `bookingReference` is never emitted either, because booking-service calls the field `reference`. The consequences:

- `BookingCreated` and `PaymentCompleted` process, because they carry `customerId`, but with an entirely empty contact record, so no channel has an address to deliver to.
- `ProviderAccepted`, `ProviderArriving`, `ProviderArrived`, `JobStarted`, `JobCompleted` and `ReviewSubmitted` have no resolvable recipient, so all six are retried three times and dead-lettered.
- `ProviderAssigned`, `ProviderRejected` and `BookingCancelled` have no producer, so nothing ever arrives.

Delivery channels are `PUSH`, `SMS`, `EMAIL`, `IN_APP`, and delivery statuses are `DELIVERED`, `PERMANENTLY_FAILED`, `SKIPPED_PREFERENCE`. All four channel adapters are logging stubs.

---

## Contract defects summary

The extraction surfaced these, all verified in code:

1. **RBAC is unconfigured in 17 of 19 services**, so every documented role restriction outside admin and reporting is unenforced.
2. **Chat channels are never activated**, because `ProviderAccepted` omits the `customerId` the consumer requires.
3. **Six of eleven notification topics always dead-letter**, and the two that process carry no contact details, so no notification can be delivered on any channel.
4. **location-service subscribes to `booking.job-started`** while the relay publishes `JobStarted`.
5. **Dispatch matching runs on null coordinates and null skill tags**, because of the `BookingCreated` schema gap.
6. **Three topics have consumers but no producer**: `ProviderAssigned`, `ProviderRejected` and `BookingCancelled`. The last matters most, since chat relies on it to close channels for cancelled bookings.
7. **Complaint events are unmapped** and land on a topic nothing reads.
8. **Two services lack the shared error envelope**: invoice-service has no handler at all, dispatch-engine only a local one.
9. **Port and route inconsistencies**, including a dispatch-engine endpoint shadowed by the admin catch-all and a routed notification-service with no controller.
10. **Duplicate coupon error codes** between promotion-service and pricing-engine for the same four constraint violations.
