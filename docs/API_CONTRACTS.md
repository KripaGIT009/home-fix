# HomeFix — JSON Contracts

Every field name and type here was read out of the Java records and controllers, not inferred. Where a producer and a consumer disagree about the same event, both shapes are shown and the mismatch is called out, because several of those disagreements are live defects.

**Companion documents:** [ARCHITECTURE.md](ARCHITECTURE.md) for diagrams, [LOCAL_ACCESS.md](LOCAL_ACCESS.md) for test users and URLs, [../CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md) for the findings audit.

---

## Platform-wide conventions

**Authentication.** The shared `JwtValidationFilter` reads `Authorization: Bearer <jwt>`, takes the user id from `sub` and roles from the `roles` claim, and grants `ROLE_<NAME>` authorities. Tokens are HS384-signed. `CorrelationIdFilter` propagates `X-Correlation-ID` into the logging context.

**Authorization, as actually configured.** The shared `RbacEnforcementFilter` matches `"METHOD /path/**" → [roles]` entries from `homefix.security.rbac.endpoint-roles`, first match wins. No service defines that map in YAML. Every servlet service populates it programmatically in a `*RbacConfig` class, eighteen in all: admin, auth, booking, catalog, chat, complaint, customer, dispatch, invoice, location, notification, payment, pricing, promotion, provider, rating-review, reporting and verification. Only api-gateway (reactive, no shared filters) and outbox-processor (no web API) have none. Where no rule matches, the filter still passes the request through, so that endpoint is **authenticated only, with no role rule configured**, whatever the Javadoc claims. Entries below say so explicitly rather than repeating the intent. Some services end their map with an `/admin/**` catch-all, which acts as deny-by-default within that prefix only. Many handlers add an ownership check on top of the role rule. The path id must be the caller's unless the caller is staff, and the staff set is usually `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `SUPPORT_AGENT`, `DISPATCHER`. A refusal is 403 `FORBIDDEN`, or a 404 where existence must not leak.

**Service-to-service calls** go to `/internal/**` paths, which the gateway does not route. They carry the shared `INTERNAL_API_KEY` in `X-Internal-Api-Key`, compared in constant time, with no default. A per-service `InternalApiKeyFilter` grants `ROLE_INTERNAL` and answers a missing or wrong key with 401 `{"errorCode":"INTERNAL_AUTH_FAILED","message":...}`, without the rest of the envelope. A user's JWT never opens these paths.

Rules are matched against the decoded path within the application, not the raw request URI. The servlet context path is stripped, one trailing slash is removed, and case is ignored. A `GET` rule also governs `HEAD`, because Spring MVC serves HEAD from the GET handler. A request the default `StrictHttpFirewall` would refuse, or one with a malformed percent-encoding, is answered 400 `{"error":"Malformed request path"}` before any rule is evaluated. That covers `;` path parameters, encoded `/`, `\`, `.` or `%`, and `//`, `/./` or `/../` segments.

**Roles.** `CUSTOMER`, `SERVICE_PROVIDER`, `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT`, `TENANT_ADMIN`. `TENANT_ADMIN` is an agency administrator, not platform staff. It cannot be self-assigned, is granted and revoked only through auth-service's internal role endpoints (called by provider-service), and opens only `/tenant/**`.

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

Exceptions to the envelope:

- `RbacEnforcementFilter` writes its own body, so an RBAC rejection returns `{"error":"Insufficient role"}`, `{"error":"Authentication required"}` or, for an ambiguous path, 400 `{"error":"Malformed request path"}`.
- `JwtValidationFilter` answers 401 `{"error":"Token has expired"}` or `{"error":"Invalid token"}`.
- The internal-key filters answer `{"errorCode":"INTERNAL_AUTH_FAILED","message":...}` only.
- dispatch-engine has only controller-local handlers, which use the envelope without a `correlationId`; its weights endpoint's bean-validation failures return Spring's default body.
- Several handlers do not map an unreadable body, a missing parameter or a type mismatch, so those get Spring's default 400 body. The service sections say where.

---

## REST contracts

### api-gateway

Port 8080. No controllers; its contract is the route table, matched in declaration order. A `/x/**` route here also lists the bare `/x` where the service has a collection endpoint.

| Path prefix | Target service |
|-------------|----------------|
| `/auth/**` | auth-service :8081 |
| `/customers/**` | customer-service :8082 |
| `/providers/**` | provider-service :8083 |
| `/bookings/**` | booking-service :8084 |
| `/catalog/**` | catalog-service :8085 |
| `/payments/**` | payment-service :8088 |
| `/pricing/**` | pricing-engine :8086 |
| `/dispatch/offers/**` | dispatch-engine :8087 |
| `/locations/**` | location-service :8092 |
| `/verifications/**` | verification-service :8094 |
| `/invoices/**` | invoice-service :8089 |
| `/complaints/**` | complaint-service :8091 |
| `/chat/**` | chat-service :8093 |
| `/reviews/**` | rating-review-service :8097 |
| `/coupons/**` | promotion-service :8098 |
| `/notifications/**` | notification-service :8090 |
| `/reports/**` | reporting-service :8096 |
| `/admin/catalog/**` | catalog-service :8085 |
| `/admin/pricing/**` | pricing-engine :8086 |
| `/admin/verifications/**`, `/admin/verification/**` | verification-service :8094 |
| `/admin/users/**` | auth-service :8081 |
| `/admin/providers/**` | provider-service :8083 |
| `/admin/bookings/**` | booking-service :8084 |
| `/admin/payments/**` | payment-service :8088 |
| `/admin/complaints/**` | complaint-service :8091 |
| `/admin/coupons/**` | promotion-service :8098 |
| `/admin/reviews/**` | rating-review-service :8097 |
| `/admin/reports/**` | reporting-service :8096 |
| `/admin/notification-templates/**` | notification-service :8090 |
| `/admin/dispatch/**` | dispatch-engine :8087 |
| `/admin/tenants/**` | provider-service :8083 |
| `/tenant/bookings/**` | booking-service :8084, declared above `/tenant/**` |
| `/tenant/**` | provider-service :8083 |
| `/admin/**` catch-all, last | admin-service :8095 |

Each admin module is now served by the service that owns its data. What still reaches admin-service through the catch-all is `/admin/dashboard`, `/admin/audit-logs`, `/admin/system-config` and `/admin/verification-queue`. `/internal/**` is not routed at all.

Routing problems worth knowing:

- notification-service is routed for `/notifications/**` but has no handler there; its only controller is `/admin/notification-templates`.
- chat-service's STOMP endpoint `/ws/chat` has no route.
- The JWT filter's public prefixes (below) do not include `/auth/logout`, `GET /catalog/**` or `/payments/callbacks/**`. Those services permit them anonymously, but through the gateway they need an active bearer token or get 401.

Global filters, in order, each rejection using the shared envelope:

| Order | Filter | Effect |
|-------|--------|--------|
| -100 | `HttpsRedirectGatewayFilter` | 301 when `X-Forwarded-Proto` is not https and enforcement is on |
| -90 | `CorrelationIdGatewayFilter` | Sets or echoes `X-Correlation-ID` |
| -80 | `WafInspectionGatewayFilter` | Regex scan of path, query values, `User-Agent`, `Referer`, `X-Forwarded-For`, `Cookie` → 400 |
| -70 | `JwtIntrospectionGatewayFilter` | Calls `GET /auth/introspect`; 401 unless the path starts with `/auth/register`, `/auth/login`, `/auth/token/refresh`, `/auth/introspect`, `/actuator`, `/health`. An active result is reused for up to `homefix.gateway.auth.introspection-cache.ttl` (default `30s`, env `INTROSPECTION_CACHE_TTL`, `0` disables) and never past the token's `exp`. Entries are keyed by the token's SHA-256 and capped at `max-entries` (default 10000, env `INTROSPECTION_CACHE_MAX_ENTRIES`). Inactive results and failed or timed-out calls are never cached, and they still give 401 |
| -60 | `OtpRateLimitGatewayFilter` | On `/auth/register/otp`, over 5 per phone per hour → 429, `Retry-After: 3600` |
| -50 | `RateLimitGatewayFilter` | 60-second window per subject: CUSTOMER 100, provider 60, default 60 → 429, `Retry-After: 60` |

---

### auth-service

Port 8081, rooted at `/auth`. Every `/auth` endpoint is public by design: registration is how a caller first obtains a token, and introspection is called by the gateway. The exceptions are the admin portal's user management under `/admin/users` (`AuthRbacConfig`: `GET` and `PATCH /admin/users/**` → ADMIN, SUPER_ADMIN) and the service-to-service surface under `/internal`, which requires the shared service credential and is not routed by the gateway.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/auth/register/otp` | public | Send a one-time code to an E.164 number |
| POST | `/auth/register/verify` | public | Verify the code, create or augment the account, return tokens |
| POST | `/auth/token/refresh` | public | Rotate the refresh token, issue a new access token |
| POST | `/auth/login/social` | public | Validate a Google or Apple identity token |
| POST | `/auth/login/password` | public | Username and password login for seeded staff accounts |
| POST | `/auth/logout` | public | Revoke a refresh token family, idempotent |
| GET | `/auth/introspect` | public | Validate a JWT and return its claims |
| GET | `/admin/users?search=` | ADMIN, SUPER_ADMIN | Admin portal account list |
| PATCH | `/admin/users/{id}/status` | ADMIN, SUPER_ADMIN | Activate, suspend or deactivate an account |
| GET | `/internal/users/{userId}/contact` | service credential | A user's delivery addresses, for notification-service and provider-service |
| GET | `/internal/users/by-mobile?mobileNumber=` | service credential | Look an account up by phone, for provider-service's Tenant flows |
| POST, DELETE | `/internal/users/{userId}/roles/{role}` | service credential | Grant or revoke `TENANT_ADMIN`, for provider-service |

**POST /auth/login/password** takes `{username, password}`, both `@NotBlank` (max 64 and 128), and answers 200 with the token body below. Five failures within 15 minutes lock the username for 30 minutes.

**Disabled accounts.** An account whose status is not `ACTIVE` gets 403 `ACCOUNT_DISABLED`, raised only after the credential itself verifies:

- on `register/verify`, before any role is added;
- on password login;
- on social login for a linked account;
- on refresh, which also revokes the token family.

Introspection answers `{"active": false}` for such an account, so the gateway refuses its access token within the introspection cache TTL. `register/otp` does not check status.

**GET /admin/users?search=** returns a bare array, newest first, at most 200, matching the mobile number or username case-insensitively. A blank search returns every account.

```json
[
  {
    "id": "bc0551ae-b3fb-4571-8587-017fa39096a4",
    "displayName": null,
    "mobileNumber": "+919000000001",
    "email": null,
    "roles": ["CUSTOMER"],
    "status": "ACTIVE",
    "createdAt": "2026-09-01T08:00:00Z"
  }
]
```

`displayName` is the username for staff accounts and null otherwise. `email` is always null, and `roles` are sorted.

**PATCH /admin/users/{id}/status** takes `{"status": "SUSPENDED"}`, `@NotNull`, one of `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, and answers the updated row.

- Nobody may change their own status (403 `SELF_STATUS_CHANGE_FORBIDDEN`).
- A target holding `ADMIN` or `SUPER_ADMIN` needs a `SUPER_ADMIN` actor (403 `SUPER_ADMIN_REQUIRED`).
- Any non-`ACTIVE` status revokes all of the account's refresh tokens.

**Internal role endpoints.** `GET /internal/users/by-mobile` takes an exact E.164 number. `POST` and `DELETE /internal/users/{userId}/roles/{role}` grant and revoke idempotently. Only `TENANT_ADMIN` is accepted (else 400 `ROLE_NOT_MANAGEABLE`), and DELETE also revokes every refresh-token family. All three answer 200 `{userId, roles, status}` with no contact details, or 404 `USER_NOT_FOUND`.

**POST /auth/register/otp**

```json
{
  "mobileNumber": "+919000000001",
  "role": "SERVICE_PROVIDER"
}
```

`mobileNumber` is `@NotBlank` and must match `^\+[1-9]\d{7,14}$`. `role` is optional and case-insensitive, defaulting to `CUSTOMER`. Only `CUSTOMER` and `SERVICE_PROVIDER` are accepted; a staff role or `TENANT_ADMIN` is refused with `INVALID_ROLE`, as is an unknown value.

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

Refresh rotates within a token family and re-resolves roles. Replaying an already-rotated token revokes the whole family with `REFRESH_TOKEN_REPLAY`. Rotation is atomic: if two refreshes present the same token at once, exactly one consumes it. The other counts as a replay and revokes the family, and that includes the successor the first refresh was issuing. Clients must therefore send one refresh at a time per session. Logout revokes the presented token's whole family (or just that token if it is unknown), returns 204 with no body and is idempotent.

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

**GET /internal/users/{userId}/contact** is service-to-service only. Events carry user ids, never contact details, so notification-service resolves a recipient's addresses here at send time. The caller must send the platform's shared service credential in `X-Internal-Api-Key` (the `INTERNAL_API_KEY` value booking-service's `/internal/**` endpoints also use). The key is compared in constant time and has no default: if auth-service has none configured it refuses every internal call. A user's JWT does not grant access, even with a staff role. The gateway has no `/internal/**` route, so this path cannot be reached from outside the cluster.

Response 200:

```json
{
  "userId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "mobileNumber": "+919000000001",
  "emailAddress": null
}
```

Every address may be null. `mobileNumber` is null for a social-login account that never registered a phone. `emailAddress` is always null today because auth-service does not store an email. The field is in the contract so that storing one later is not a breaking change. The response is PII and is never logged.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation failed (`details` lists `field: message`), or an unreadable body, a non-UUID path id or a missing parameter |
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
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` called without the right `X-Internal-Api-Key`, or with none configured |
| 404 | `USER_NOT_FOUND` | No account for that id or number: internal endpoints and the admin status change |
| 401 | `INVALID_CREDENTIALS` | Password login failed |
| 429 | `ACCOUNT_LOCKED` | Password login locked after 5 failures; sends `Retry-After` |
| 403 | `ACCOUNT_DISABLED` | The account is not `ACTIVE`; see above |
| 403 | `SELF_STATUS_CHANGE_FORBIDDEN` | A staff member changing their own status |
| 403 | `SUPER_ADMIN_REQUIRED` | An `ADMIN` changing an `ADMIN` or `SUPER_ADMIN` account |
| 400 | `ROLE_NOT_MANAGEABLE` | Internal grant or revoke of any role but `TENANT_ADMIN` |
| 401 | `INVALID_PRINCIPAL` | Admin endpoint with a non-UUID subject |
| 502 | `SMS_DELIVERY_FAILED` | Gateway failed; no pending session was created |

#### Email sign-up, email sign-in, password reset, credentials and invitations

Full design: `.kiro/specs/email-auth-and-signup/design.md`. Codes are 6 digits, live 10 minutes and
allow 5 wrong attempts; one address gets at most one code a minute and five an hour, and sign-up, reset
and invitation acceptance are limited per client IP (429 `TOO_MANY_REQUESTS` with `Retry-After`).
Passwords: 8–72 characters with a letter and a digit (400 `WEAK_PASSWORD`). `TokenResponse` is the body
every sign-in returns (above).

**Public** (no token)

| Method & path | Body | Answer |
|---|---|---|
| `POST /auth/register/email` | `{displayName (2-80), email, mobileNumber (E.164), password, role}` — `CUSTOMER` (default) or `SERVICE_PROVIDER` | 202 `{status:"CODE_SENT", expiresInSeconds}`, the same for a new, pending or registered address (a registered one is emailed "you already have an account" instead of a code); 409 `MOBILE_IN_USE`; 400 `INVALID_ROLE` for any other role |
| `POST /auth/register/email/verify` | `{email, code}` | 200 `TokenResponse`; 400 `INVALID_CODE`; 410 `CODE_EXPIRED` |
| `POST /auth/register/email/resend` | `{email}` | 202 as above, a code only if a sign-up is waiting |
| `POST /auth/login/password` | `{identifier, password}` — email or username; `username` still accepted | 200 `TokenResponse`; 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED` (right password, code never entered) / `ACCOUNT_DISABLED`; 429 `ACCOUNT_LOCKED` |
| `POST /auth/password/forgot` | `{email}` | 202 as above, always |
| `POST /auth/password/reset` | `{email, code, newPassword}` | 204, every refresh session of the account revoked and any lockout lifted; 400 `INVALID_CODE`; 410 `CODE_EXPIRED` |
| `GET /auth/invitations/{token}` | — | `{email, role, invitedByName, expiresAt, existingAccount}`; 410 `INVITATION_EXPIRED` for an unknown, used, revoked or expired link |
| `POST /auth/invitations/{token}/acceptance` | new account `{displayName, mobileNumber, password}`; existing account `{password}` (its own) | 200 `TokenResponse` with the invited role; 401 `INVALID_CREDENTIALS`; 409 `MOBILE_IN_USE` / `PASSWORD_NOT_SET`; 410 |

**Signed in** (any role; the account is always the token's subject)

| Method & path | Body | Answer |
|---|---|---|
| `GET /auth/me` | — | `{userId, displayName, email, emailVerified, mobileNumber, username, hasPassword, roles[]}` (`email` null unless verified) |
| `POST /auth/me/email` | `{email, currentPassword?}` (required when a password is set) | 202 code sent to the new address; 403 `CURRENT_PASSWORD_INCORRECT`; 409 `EMAIL_IN_USE` |
| `POST /auth/me/email/verify` | `{code}` | 200, the `GET /auth/me` body with the new email |
| `PUT /auth/me/password` | `{currentPassword?, newPassword}` | 204; 400 `EMAIL_REQUIRED` (no email or username to sign in with); 403 `CURRENT_PASSWORD_INCORRECT` |

**Staff invitations** (ADMIN, SUPER_ADMIN; gateway route `/admin/invitations/**` → auth-service)

| Method & path | Body | Answer |
|---|---|---|
| `GET /admin/invitations` | — | `Invitation[]` still usable, newest first |
| `POST /admin/invitations` | `{email, role}` — `ADMIN` (SUPER_ADMIN only), `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT` | 201 `Invitation`; re-inviting an address revokes its open invitation; 400 `INVALID_ROLE` (never `SUPER_ADMIN` or `TENANT_ADMIN`); 403 `SUPER_ADMIN_REQUIRED` |
| `DELETE /admin/invitations/{id}` | — | 204; 404 `INVITATION_NOT_FOUND` |

`Invitation = {id, email, role, invitedBy, invitedByName, createdAt, expiresAt}`. Only the SHA-256 of the
link's token is stored.

**Internal** (`X-Internal-Api-Key`)

| Method & path | Body | Answer |
|---|---|---|
| `POST /internal/emails/agency-decision` | `{userId, tenantName, approved, reason?}` | 202 (provider-service asks; auth-service owns email delivery) |

`GET /internal/users/{userId}/contact` now returns the account's `emailAddress` once it is verified.
Admin user rows (`GET /admin/users`) carry `email` and `displayName`, and `status` may be
`PENDING_VERIFICATION` (an email sign-up whose code was never entered; removed after 24 hours).

---

### customer-service

Port 8082, rooted at `/customers`, plus one service-to-service endpoint under `/internal`. Roles come from `CustomerRbacConfig`. Every handler also requires the path `{id}` to be the caller unless the caller is staff, otherwise 403 `FORBIDDEN`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| PUT | `/customers/{id}/profile` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; owner or staff | Update name, email, photo, multipart |
| POST | `/customers/{id}/location/detect` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; owner or staff | Resolve a service location from device GPS |
| POST | `/customers/{id}/deletion` | CUSTOMER, ADMIN, SUPER_ADMIN; owner or staff | Request account and data deletion |
| POST | `/customers/{id}/addresses` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; owner or staff | Add a saved address from coordinates |
| DELETE | `/customers/{id}/addresses/{addressId}` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; owner or staff | Delete a saved address |
| GET | `/internal/addresses/{addressId}` | service credential | An address's owner, label and coordinates, for dispatch-engine and booking-service |

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

**DELETE /customers/{id}/addresses/{addressId}** returns 204. A missing address gives 404 `CUSTOMER_NOT_FOUND`. Booking-service is then asked whether an active booking references the address, through `GET /internal/bookings/active?customerId=&addressId=` with `X-Internal-Api-Key`. A non-null `bookingReference` gives `ADDRESS_IN_USE`. An unreachable or refusing booking-service gives `BOOKING_SERVICE_UNAVAILABLE` rather than risking a wrong delete. A booking waiting in a Tenant queue (`AWAITING_ASSIGNMENT`) does not count as active. Deleting the default promotes the most recently created remaining address.

**GET /internal/addresses/{addressId}** is service-to-service only. `BookingCreated` carries the booking's `addressId`, not coordinates, so dispatch-engine resolves it here before matching a provider. booking-service reads it too, for the job address on the booking detail and the Tenant queue. The caller must send the platform's shared service credential in `X-Internal-Api-Key` (the `INTERNAL_API_KEY` value booking-service's and auth-service's `/internal/**` endpoints also use; customer-service reads it as `homefix.customer.internal-api-key`). The key is compared in constant time and has no default: with none configured every internal call is refused. A user's JWT does not grant access, even the address owner's or a staff role. The gateway routes only `/customers/**` here and has no `/internal/**` route, so this path cannot be reached from outside the cluster.

Response 200:

```json
{
  "addressId": "c4e7a1b9-3d28-4f06-9a15-8b6d2e0f7c43",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "lat": 12.971599,
  "lng": 77.594566,
  "label": "Home"
}
```

Coordinates are always present: they are stored even when reverse-geocoding fails. `label` is the customer's own name for the address, and may be null; booking-service passes it to the assigned provider. The encrypted street address is deliberately not returned. Addresses are hard-deleted, so a deleted address is a 404.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation failed |
| 400 | `GPS_UNAVAILABLE` | `gpsDenied` true, or a coordinate missing |
| 400 | `INVALID_PHOTO_TYPE` | Photo part is not JPEG or PNG |
| 400 | `PHOTO_TOO_LARGE` | Photo part over 5 MB |
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` called without the right `X-Internal-Api-Key`, or with none configured |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 403 | `FORBIDDEN` | The path id is not the caller and the caller is not staff |
| 404 | `CUSTOMER_NOT_FOUND` | Address not found for the customer, or no such profile |
| 404 | `ADDRESS_NOT_FOUND` | `/internal/addresses/{addressId}` for an id with no address |
| 409 | `ADDRESS_IN_USE` | An active booking references the address |
| 422 | `ADDRESS_LIMIT_REACHED` | 10 active addresses already exist |
| 503 | `BOOKING_SERVICE_UNAVAILABLE` | Booking-service lookup failed |

---

### provider-service

Port 8083. `/providers/{id}` for the provider's own profile and wallet, `/admin/providers` for the admin portal, `/internal/providers` for other services, and the Tenant surfaces (see Tenants below). Roles come from `ProviderRbacConfig`. Every `/providers/{id}` handler then resolves `{id}`: the literal `me` (any case) means the JWT subject, and anything that is neither `me` nor a UUID gives 400 `INVALID_PROVIDER_ID`. Then it asserts ownership. The path id must be the caller's unless the caller is staff (`ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `SUPPORT_AGENT`, `DISPATCHER`), otherwise 403 `FORBIDDEN`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/providers/{id}/profile` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, DISPATCHER, SUPPORT_AGENT; owner or staff | Read the profile |
| PUT | `/providers/{id}/profile` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN; owner or staff | Create or replace categories, skills, experience, radius, name, base location |
| PUT | `/providers/{id}/radius` | same | Update the service radius |
| PUT | `/providers/{id}/availability` | same | Replace the weekly schedule |
| PUT | `/providers/{id}/emergency-availability` | same | Toggle emergency availability |
| POST | `/providers/{id}/settlements` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, FINANCE_ADMIN; owner or staff | Request a wallet settlement |
| GET | `/providers/{id}/settlements` | same | Settlement history, newest first |
| GET | `/providers/{id}/earnings?page=&size=` | same | Paginated earnings history |
| GET | `/providers/{id}/summary` | same | Dashboard: wallet and today's earnings |
| GET | `/providers/{id}/settlement-info` | same | Available balance and masked bank accounts |
| GET | `/providers/{id}/active-jobs` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, DISPATCHER, SUPPORT_AGENT; owner or staff | In-flight jobs for the dashboard |
| GET | `/admin/providers?search=` | ADMIN, SUPER_ADMIN | Admin portal list |
| PATCH | `/admin/providers/{id}/status` | ADMIN, SUPER_ADMIN | Suspend or reinstate |
| GET | `/internal/providers/eligible` | service credential | Candidates for dispatch-engine |
| GET | `/internal/providers/summaries?ids=` | service credential | Names and primary skills by id |
| POST | `/internal/providers/{providerId}/earnings` | service credential | Credit a paid booking's earning, from payment-service |

`/internal/**` requires `X-Internal-Api-Key` matching `homefix.provider.internal-api-key` (`INTERNAL_API_KEY`), compared in constant time, with no default. A missing or wrong key gets 401 `{"errorCode":"INTERNAL_AUTH_FAILED","message":...}` from the filter, without the rest of the envelope. The gateway has no `/internal/**` route.

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
  "serviceRadiusKm": 25,
  "baseLatitude": 12.971599,
  "baseLongitude": 77.594566
}
```

`displayName` is at most 100 characters. `categories` is `@NotNull` with at most 5 entries, each carrying at most 10 subcategory ids. `skillTags` is `@NotNull` with 1 to 20 entries. `yearsExperience` and `serviceRadiusKm` are unannotated primitives; the service enforces 0 to 50 and 1 to 100 km. `baseLatitude` (−90 to 90) and `baseLongitude` (−180 to 180) are the point dispatch measures distance from. They must be sent together or not at all (else `VALIDATION_ERROR`), and omitting both keeps the stored location. This endpoint is an upsert: it creates the profile when none exists. The other three writes give 404 `PROVIDER_NOT_FOUND` without one.

Response 200, also returned by the profile read and the three other updates:

```json
{
  "id": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "displayName": "Rohit Kumar Electricals",
  "yearsExperience": 7,
  "serviceRadiusKm": 25,
  "baseLatitude": 12.971599,
  "baseLongitude": 77.594566,
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

The base coordinates are null until set. The encrypted bank reference is never exposed, only `bankAccountVerified`. `underReview` flips when the rating falls below the configured threshold of 3.0.

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

**Dashboard reads for a provider with no profile yet.** A newly registered provider can open the dashboard before saving a profile, so these reads answer empty rather than 404:

- `summary`: zeros.
- `settlement-info`: zero balance, `bankAccounts: []`.
- `earnings`: an empty page.
- `GET settlements`: `[]`.

`GET /profile` still gives 404 `PROVIDER_NOT_FOUND`.

- **GET /providers/{id}/summary** returns `{walletBalance, todayEarnings, todayJobCount, currency}`. "Today" is the civil day in Asia/Kolkata, and only `JOB_CREDIT` rows count as jobs.
- **GET /providers/{id}/settlement-info** returns `{availableBalance, currency, bankAccounts: [{id, masked, verified}]}`.
- **GET /providers/{id}/active-jobs** returns `[{bookingId, reference, serviceName, status, isEmergency, scheduledAt, customerArea, estimatedEarning}]`, read from booking-service's `GET /internal/bookings/provider/{providerId}/active`. `customerArea` is always an empty string, and the list degrades to `[]` when a downstream call fails.

**GET /internal/providers/eligible?subcategoryId=&lat=&lon=&radiusKm=&emergency=&skillTags=a&skillTags=b** returns `{providers: [{providerId, distanceScore, availabilityScore, ratingScore, skillScore, performanceScore}]}`, closest first.

- Only verification-`APPROVED` providers within the radius of their base location are returned.
- An unreachable verification-service gives an empty list (fail closed), and so does an empty `skillTags`.
- `subcategoryId` is accepted but does not filter.
- A bad or missing coordinate or radius gives 400 `VALIDATION_ERROR`.

**GET /internal/providers/summaries?ids=...** returns `{providers: [{id, displayName, primarySkill}]}`. Unknown ids are left out, and more than 200 ids give 400 `VALIDATION_ERROR`.

**POST /internal/providers/{providerId}/earnings**

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "bookingReference": null,
  "gross": 1250.00,
  "platformFee": 250.00
}
```

`bookingId` is `@NotNull`. `gross` and `platformFee` are `@NotNull`, at least 0.00, at most two decimals, and a fee above the gross gives 400 `VALIDATION_ERROR`. `bookingReference` is optional. The net is computed here and credited to the wallet as a `JOB_CREDIT`. Response 200 is `{providerId, walletBalance}`. **Idempotent per booking:** if a `JOB_CREDIT` for that `bookingId` already exists, the call answers 200 with the current balance and credits nothing, and a partial unique index backs that up. An unknown provider gives 404 `PROVIDER_NOT_FOUND`.

**GET /admin/providers?search=** returns a bare array, newest first, at most 200, filtered by a case-insensitive substring of `displayName`:

```json
[
  {
    "id": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
    "displayName": "Rohit Kumar Electricals",
    "mobileNumber": null,
    "primarySkill": "wiring",
    "verificationStatus": "APPROVED",
    "rating": 4.60,
    "completedJobs": null,
    "isOnline": null,
    "status": "ACTIVE"
  }
]
```

`primarySkill` is the first skill tag. `mobileNumber`, `completedJobs` and `isOnline` are always null. `verificationStatus` and `status` (`ACTIVE` or `SUSPENDED`) are null when verification-service cannot say.

**PATCH /admin/providers/{id}/status** takes `{"status": "SUSPENDED"}`, `@NotBlank`, case-insensitive. It drives verification-service's `/internal/verifications/{id}/suspend` or `/reinstate` and answers the updated row.

- `ACTIVE` reinstates and `SUSPENDED` suspends.
- `DEACTIVATED` gives 400 `UNSUPPORTED_PROVIDER_STATUS`, and any other value 400 `VALIDATION_ERROR`.
- A provider with no verification record gives 409 `VERIFICATION_NOT_FOUND`.
- Other verification 4xx answers pass through with their status and code, for example 409 `INVALID_STATE_TRANSITION`.
- An unreachable verification-service gives 503 `VERIFICATION_UNAVAILABLE`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, the service range checks, or a missing, mistyped or unreadable parameter or body |
| 400 | `INVALID_PROVIDER_ID` | `{id}` is neither `me` nor a UUID |
| 400 | `CATEGORY_DEACTIVATED` | Selected category deactivated or absent |
| 400 | `SUBCATEGORY_DEACTIVATED` | Selected subcategory deactivated or absent |
| 400 | `OVERLAPPING_AVAILABILITY` | Two submitted slots overlap on one day |
| 400 | `SETTLEMENT_AMOUNT_TOO_LOW` | Below the 1.00 minimum |
| 400 | `SETTLEMENT_AMOUNT_EXCEEDS_BALANCE` | Over the wallet balance |
| 400 | `NO_VERIFIED_BANK_ACCOUNT` | No verified account on file |
| 400 | `UNSUPPORTED_PROVIDER_STATUS` | Admin status `DEACTIVATED` |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` without the right key (filter body) |
| 403 | `FORBIDDEN` | Path id is not the caller and the caller is not staff |
| 404 | `PROVIDER_NOT_FOUND` | No profile for that id |
| 409 | `VERIFICATION_NOT_FOUND` | Admin status change for a provider with no verification record |
| 503 | `VERIFICATION_UNAVAILABLE` | verification-service unreachable on an admin status change |

An encryption failure, and two earning credits for one booking racing into the unique index, have no handler and surface as a generic 500.

---

### verification-service

Port 8094 locally. Provider paths under `/verifications/{providerId}`, admin paths under `/admin/verifications/{providerId}` and, for the admin portal's queue, `/admin/verification`. Service-to-service paths are under `/internal/verifications`. Roles come from `VerificationRbacConfig`, whose `/admin/**` rules cover every method. The ownership checks give 403 `FORBIDDEN`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/verifications/{providerId}/documents` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN; caller must be `providerId` or staff | Upload required documents, multipart |
| GET | `/verifications/{providerId}` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; caller must be `providerId` or staff | Read the record including its audit trail |
| GET | `/verifications/{providerId}/job-assignment-eligibility` | SERVICE_PROVIDER, DISPATCHER, ADMIN, SUPER_ADMIN; no ownership check, by design | Assert dispatch eligibility, 204 or 403 |
| POST | `/admin/verifications/{providerId}/verify-documents` | ADMIN, SUPER_ADMIN | Mark documents verified, auto-start the check |
| POST | `/admin/verifications/{providerId}/background-check-result` | ADMIN, SUPER_ADMIN | Record the check result |
| POST | `/admin/verifications/{providerId}/approve` | ADMIN, SUPER_ADMIN | Approve, or reinstate a suspended provider |
| POST | `/admin/verifications/{providerId}/reject` | ADMIN, SUPER_ADMIN | Reject with a required reason |
| POST | `/admin/verifications/{providerId}/suspend` | ADMIN, SUPER_ADMIN | Suspend and remove from the dispatch pool |
| GET | `/admin/verification/queue` | ADMIN, SUPER_ADMIN | Providers in `DOCUMENT_SUBMITTED`, oldest submission first, at most 200 |
| GET | `/admin/verification/{providerId}/documents` | ADMIN, SUPER_ADMIN | A provider's submitted documents |
| POST | `/admin/verification/{providerId}/decision` | ADMIN, SUPER_ADMIN | Approve or reject the documents, 204 |
| GET | `/admin/verification/background-checks` | ADMIN, SUPER_ADMIN | Providers in `BACKGROUND_CHECK_PENDING` or `BACKGROUND_CHECK_COMPLETED`, oldest check first, at most 200 |
| POST | `/admin/verification/{providerId}/background-check` | ADMIN, SUPER_ADMIN | Record the check's result and approve or reject in one step, 204 |
| POST | `/internal/verifications/approved` | service credential | Which of the given providers are `APPROVED` |
| POST | `/internal/verifications/statuses` | service credential | Current status per provider |
| POST | `/internal/verifications/{providerId}/suspend` | service credential | Suspend on an admin's behalf, for provider-service |
| POST | `/internal/verifications/{providerId}/reinstate` | service credential | Reinstate a suspended provider, for provider-service |

The provider-facing upload handler binds the parts as `@RequestParam Map<String, MultipartFile>`, so **each part name is a document type**: `GOVERNMENT_ID`, `ADDRESS_PROOF`, `SKILL_CERTIFICATION`. All three are required; a missing one gives `MISSING_REQUIRED_DOCUMENTS` listing them, and an unrecognised part name gives `VALIDATION_ERROR`. No per-part size or MIME allow-list is enforced on this path.

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

**GET /admin/verification/queue** returns `[{providerId, displayName, mobileNumber, primarySkill, submittedAt, documentCount}]`, ordered by latest upload.

- `displayName` and `primarySkill` come from provider-service's `GET /internal/providers/summaries`, and are null when it cannot answer.
- `mobileNumber` is always null.

**GET /admin/verification/{providerId}/documents** returns `[{id, type, contentType, url, fileName}]`. `type` is a label such as "Government ID", and `url` and `fileName` are always null. It gives 404 `VERIFICATION_NOT_FOUND` for an unknown provider.

**GET /admin/verification/background-checks** returns `[{providerId, displayName, primarySkill, status, startedAt, result, documentCount}]`.

**POST /admin/verification/{providerId}/background-check** takes `{"outcome": "PASSED" | "FAILED", "result": "...", "reason": "..."}`: `result` is required (at most 2000) and kept on the record; `reason` (at most 1000) is required for `FAILED` and sent to the provider. `PASSED` moves the provider to `APPROVED`, `FAILED` to `REJECTED`, recording `BACKGROUND_CHECK_COMPLETED` first when needed. 409 `INVALID_STATE_TRANSITION` for a provider not at this step (a suspended provider is reinstated with `/approve`, not here). No background-check vendor is integrated, so this is how a check completes.

**POST /admin/verification/{providerId}/decision** takes `{"decision": "APPROVE" | "REJECT", "reason": "..."}` (`reason` at most 1000, required for `REJECT`). The acting admin is the JWT subject.

- `APPROVE` marks the documents verified and starts the background check. It does not approve the provider for jobs.
- `REJECT` rejects.
- An unknown `decision` gives 400 `VALIDATION_ERROR`.

**Internal endpoints** need the shared `X-Internal-Api-Key`.

- `approved` and `statuses` take `{"providerIds": [...]}`, `@NotNull`, at most 500 ids, and answer `{approvedProviderIds: [...]}` (sorted) and `{statuses: {"<id>": "<status>"}}` (ids with no record left out).
- `suspend` and `reinstate` take `{actorId, reason?}` and answer `{providerId, status}`. `reinstate` accepts only `SUSPENDED` and otherwise gives 409 `INVALID_STATE_TRANSITION`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | No documents, unknown part name, missing rejection reason |
| 400 | `MISSING_REQUIRED_DOCUMENTS` | Parts do not cover all required types |
| 400 | `DOCUMENT_READ_FAILED` | IO error reading a part |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or the subject is not a UUID |
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` without the right key (filter body) |
| 403 | `FORBIDDEN` | Not the provider and not staff |
| 403 | `PROVIDER_NOT_APPROVED` | Eligibility checked while not approved |
| 404 | `VERIFICATION_NOT_FOUND` | No record for that provider |
| 409 | `INVALID_STATE_TRANSITION` | Transition not permitted |

---

### booking-service

Port 8084 under compose (the `application.yml` default is 8085). `/bookings` for creation, customer actions and job execution, `/admin/bookings` for the admin portal, `/tenant/bookings` for Tenant admins (see Tenants below), and `/internal/bookings` for other services. Every `{key}` (`{reference}`, `{bookingKey}`, `{id}`) accepts either the booking UUID or its human-readable reference (`HFX-yyyyMMdd-XXXXXX`). A canonical 36-character UUID is looked up by id, anything else by reference, so the two forms cannot be confused.

The acting principal is never read from the body. The actor id is the JWT subject, and the actor role is the first authority with `ROLE_` stripped, defaulting to `CUSTOMER` in the booking controller and `SERVICE_PROVIDER` in the job execution controller.

**Roles.** `BookingRbacConfig` registers rules only for the reads, the admin paths and the Tenant paths, in this order:

| Rule | Roles |
|------|-------|
| `GET /bookings/history` | CUSTOMER plus staff |
| `GET /bookings/*` | CUSTOMER, SERVICE_PROVIDER plus staff |
| `GET /admin/bookings/**`, `POST /admin/bookings/**` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT, DISPATCHER |
| `GET /tenant/bookings/**`, `POST /tenant/bookings/**` | TENANT_ADMIN |

Staff here is `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `SUPPORT_AGENT` and `DISPATCHER`. The `POST /bookings/**` commands have no role rule. **Ownership is enforced in code instead** (`BookingAccess`). Each command admits a fixed set of callers, staff always included. Anyone else gets the same 404 `BOOKING_NOT_FOUND` as for a missing booking, so a reference cannot be probed. The staff test on commands looks only at the actor role, which is the token's first authority.

| Method | Path | Who may call | Purpose |
|--------|------|--------------|---------|
| POST | `/bookings` | any authenticated caller; the customer is the JWT subject | Create a booking, JSON, returns the estimate |
| POST | `/bookings/media` | same | Same flow, multipart, up to 10 files |
| POST | `/bookings/{reference}/confirmation` | customer, staff | Confirm; `CREATED` → `SEARCHING_PROVIDER`, publishes `BookingCreated` |
| POST | `/bookings/{reference}/cancellation` | customer, assigned provider, staff | Cancel, applying the fee policy |
| POST | `/bookings/{reference}/on-the-way` | assigned provider, staff | → `PROVIDER_ON_THE_WAY` |
| POST | `/bookings/{reference}/arrived` | assigned provider, staff | → `PROVIDER_ARRIVED` |
| POST | `/bookings/{reference}/photos` | assigned provider, staff | Attach one before or after photo, 204 |
| POST | `/bookings/{reference}/start` | assigned provider, staff | → `JOB_STARTED`, requires a before photo |
| POST | `/bookings/{reference}/pause` | assigned provider, staff | → `JOB_PAUSED`, reason required |
| POST | `/bookings/{reference}/resume` | assigned provider, staff | → `JOB_STARTED` |
| POST | `/bookings/{reference}/parts` | assigned provider, staff | Add a parts line item → `CUSTOMER_APPROVAL_PENDING` |
| POST | `/bookings/{reference}/quote/approval` | customer, staff | Approve → `JOB_STARTED` |
| POST | `/bookings/{reference}/quote/rejection` | customer, staff | Reject → `JOB_COMPLETED` at the original price |
| POST | `/bookings/{reference}/complete` | assigned provider, staff | → `JOB_COMPLETED`, requires an after photo |
| POST | `/bookings/{bookingKey}/assignment/acceptance` | the assigned provider only, not staff | Accept a Tenant assignment; see Tenants |
| POST | `/bookings/{bookingKey}/assignment/rejection` | the assigned provider only, not staff | Decline a Tenant assignment; see Tenants |
| GET | `/bookings/history` | CUSTOMER, staff | The caller's own bookings, newest first, paged |
| GET | `/bookings/{bookingId}` | CUSTOMER, SERVICE_PROVIDER, staff; plus ownership | One booking |
| GET | `/admin/bookings?search=&status=` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT, DISPATCHER | Admin portal list |
| POST | `/admin/bookings/{id}/cancel` | same | Force-cancel with a reason |
| GET | `/internal/bookings/{bookingId}/payment-facts` | service credential | What payment-service charges |
| POST | `/internal/bookings/{bookingId}/payment-pending` | service credential | Customer started paying → `PAYMENT_PENDING` |
| POST | `/internal/bookings/{bookingId}/provider-accepted` | service credential | dispatch-engine: a provider accepted the offer |
| POST | `/internal/bookings/{bookingId}/searching-failed` | service credential | dispatch-engine: nobody accepted |
| GET | `/internal/bookings/provider/{providerId}/active` | service credential | A provider's in-flight jobs, for provider-service's dashboard |
| GET | `/internal/bookings/active?customerId=&addressId=` | service credential | Whether an active booking uses an address, for customer-service |

`/internal/**` requires `X-Internal-Api-Key` equal to `homefix.booking.internal-api-key` (`INTERNAL_API_KEY`, no default), compared in constant time. A missing or wrong key gets 401 `{"errorCode":"INTERNAL_AUTH_FAILED","message":...}` from the filter, without the rest of the envelope. The gateway has no `/internal/**` route. The internal endpoints take the booking UUID only; a non-UUID gives 400 `VALIDATION_ERROR`.

**POST /bookings**

```json
{
  "categoryId": "3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34",
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "addressId": "a41d8f62-5c73-4e19-b8a0-62f3d9e1c405",
  "emergency": false,
  "scheduledAt": "2026-09-12T10:00:00Z",
  "description": "Kitchen tap is leaking at the base.",
  "couponCode": "SAVE20"
}
```

Category, subcategory and `addressId` are `@NotNull`. When `emergency` is true the booking is driven to `SEARCHING_PROVIDER` in the same request and `scheduledAt` is set to now, so every booking has a `scheduledAt`. Otherwise `scheduledAt` is required, at least 2 hours ahead and at most 90 days out. `description` has a 2000-character cap and is not persisted. `couponCode` is optional, at most 32 characters, and is forwarded to pricing-engine.

Response 201:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
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

The same body (`BookingResponse`) is returned by every command except photos, and by the acceptance and rejection endpoints. `estimate` is populated only on the two creation endpoints; every other response carries `null` there.

**POST /bookings/media** is multipart with text parts `categoryId`, `subcategoryId`, `addressId` (required), `emergency`, `scheduledAt`, `description`, `couponCode` and a repeatable `media` file part.

- At most 10 files per booking, and the provider's job photos count toward the same cap.
- Content type must be `image/jpeg`, `image/png`, `video/mp4` or `video/quicktime`.
- The service checks 50 MB per file, but no multipart limits are configured, so Spring Boot's defaults (1 MB per file, 10 MB per request) apply first.
- A malformed `scheduledAt` on this path throws an unhandled parse exception and surfaces as a 500.

**POST /bookings/{reference}/photos** is multipart with a `type` text part (`BEFORE_PHOTO` or `AFTER_PHOTO`, trimmed and case-insensitive) and a single `file` part. It returns **204 with no body** and performs no state change.

**POST /bookings/{reference}/cancellation** takes an optional `{"reason": "..."}` with a 500-character cap. The response carries the applied `cancellationFee`. The fee is 0.00 from `SEARCHING_PROVIDER`, `AWAITING_ASSIGNMENT`, `PROVIDER_ASSIGNED` and `PROVIDER_ACCEPTED`. The configured fee applies from `PROVIDER_ON_THE_WAY`, and with no per-subcategory fee source today that is the 0.00 default. Cancellation is reachable from those five states only. From a later state the answer is 409 `CANCELLATION_NOT_ALLOWED` or 409 `INVALID_BOOKING_TRANSITION`. A `CREATED` booking cannot be cancelled.

**POST /bookings/{reference}/pause** requires `{"reason": "..."}`, `@NotBlank` with 1 to 500 characters, re-checked after trimming. It closes the open work interval and opens a pause interval.

**POST /bookings/{reference}/parts**

```json
{ "itemName": "Ceramic cartridge 40mm", "quantity": 2, "unitCost": 450.00 }
```

`itemName` is `@NotBlank` max 200, `quantity` at least 1, `unitCost` `@NotNull` at least 0.01. The response always reports `CUSTOMER_APPROVAL_PENDING`, because the handler performs two transitions in one request. The aggregate parts total goes to pricing-engine and the recalculated value is stored as the final total. The response's `estimatedTotal` keeps the original; the new total shows as the detail's `amount`. An unreachable pricing-engine, or one answering 4xx, gives `PRICING_ENGINE_UNAVAILABLE`.

Net duration is the sum of work intervals minus pause intervals, and is exposed as the detail's `netDurationSeconds` once the job is complete.

**POST /admin/bookings/{id}/cancel** takes `{"reason": "..."}`, `@NotBlank`, at most 500 characters. It runs the same cancellation as a customer's, acting as the caller's alphabetically first staff role, and answers the cancelled booking as an admin list row.

**GET /admin/bookings?search=&status=** returns a bare array, newest first, at most 200. `search` is a case-insensitive substring of the reference, or an exact UUID. `status` is one booking status, and an unknown value gives 400 `VALIDATION_ERROR`.

```json
[
  {
    "id": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
    "reference": "HFX-20260912-AB12CD",
    "customerName": null,
    "providerName": null,
    "serviceName": "Tap repair",
    "status": "PROVIDER_ACCEPTED",
    "isEmergency": false,
    "totalAmount": 1250.00,
    "currency": "INR",
    "createdAt": "2026-09-10T07:41:22.512Z",
    "scheduledAt": "2026-09-12T10:00:00Z"
  }
]
```

`customerName` and `providerName` are always null: the names live in auth-service and provider-service.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, a mistyped parameter or path id, missing `scheduledAt`, bad photo type |
| 401 | `INVALID_PRINCIPAL` / `UNAUTHENTICATED` | Reads, admin, Tenant and assignment endpoints: non-UUID subject, or no principal |
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` without the right key (filter body) |
| 403 | `FORBIDDEN` | Admin endpoint reached without a staff role |
| 404 | `BOOKING_NOT_FOUND` | No booking for that key, or the caller may not see or act on it |
| 409 | `INVALID_BOOKING_TRANSITION` | Target state not permitted from the current state |
| 409 | `CANCELLATION_NOT_ALLOWED` | Cancellation from a state the fee policy does not cover |
| 409 | `BOOKING_CHANGED` | Another transaction changed the booking first (optimistic lock); reload and retry |
| 409 | `BOOKING_NOT_PAYABLE` | `payment-pending` from a non-payable state |
| 422 | `SERVICE_UNAVAILABLE` | Category or subcategory inactive in the catalog |
| 422 | `LEAD_TIME_TOO_SHORT` | Scheduled sooner than 2 hours out |
| 422 | `SCHEDULING_HORIZON_EXCEEDED` | Scheduled further than 90 days out |
| 422 | `MEDIA_VALIDATION_ERROR` | Over 10 files, empty file, over 50 MB, or a disallowed type |
| 422 | `PHOTO_REQUIRED` | Start without a before photo, complete without an after photo |
| 503 | `PRICING_ENGINE_UNAVAILABLE` | Pricing-engine unreachable or refusing |
| 500 | `BOOKING_NOT_COMPLETED` | The booking saga failed and was compensated; also a confirmation of a booking that is no longer `CREATED` |

The Tenant codes are listed under Tenants. A missing or malformed body or parameter is not handled and gets Spring's default error body. On the two command controllers, a non-UUID subject surfaces as a 500.

**GET /bookings/history?page=1&pageSize=10** lists the caller's own bookings (customer id = JWT subject), newest first by creation time. `page` is **1-based** in the request and the response, matching the customer app's pagination control; it defaults to 1. `pageSize` is 1 to 50 and defaults to 10. A page past the end returns `items: []` with the real totals. Staff are admitted but see only their own bookings; this endpoint never lists anybody else's. A provider-only account gets 403.

```json
{
  "items": [
    {
      "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
      "referenceNumber": "HFX-20260912-AB12CD",
      "status": "PROVIDER_ACCEPTED",
      "serviceName": "Tap repair",
      "date": "2026-09-12T10:00:00Z",
      "amount": 1250.00,
      "currency": "INR"
    }
  ],
  "page": 1,
  "pageSize": 10,
  "totalItems": 1,
  "totalPages": 1
}
```

`date` is `scheduledAt`, or `createdAt` when there is none. `amount` is `finalTotal` once set, otherwise `estimatedTotal`. `status` is the booking-state enum name. `serviceName` comes from catalog-service's `GET /catalog/categories` (one call per page). When the catalog is unreachable, or the subcategory has since been deactivated, it is `"Service"`. A catalog failure never fails the read.

**GET /bookings/{bookingId}** takes the booking UUID (what the customer app routes on) or its reference. The booking is visible to:

- its customer;
- its assigned provider (`providerId` = JWT subject);
- staff, meaning a caller holding any of `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `SUPPORT_AGENT` or `DISPATCHER`.

Anyone else gets the same 404 `BOOKING_NOT_FOUND` as for a booking that does not exist. The provider app reads the same endpoint for its job screens.

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "referenceNumber": "HFX-20260912-AB12CD",
  "status": "JOB_COMPLETED",
  "serviceName": "Tap repair",
  "date": "2026-09-12T10:00:00Z",
  "amount": 1250.00,
  "currency": "INR",
  "emergency": false,
  "scheduledAt": "2026-09-12T10:00:00Z",
  "createdAt": "2026-09-10T07:41:22.512Z",
  "providerId": "c81f2a90-6d3e-4b57-a1c4-0e9f7b2d8a63",
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "address": "Home",
  "coordinates": { "latitude": 12.971599, "longitude": 77.594566 },
  "photos": [
    { "id": "1e7b4d09-2c51-4f8a-9b36-0d4e7a1c5f82", "kind": "BEFORE", "uploadedAt": "2026-09-12T10:20:00Z" }
  ],
  "parts": [
    { "id": "4c2a8e61-7f03-4b95-a1d8-3e6f9b0c2d47", "itemName": "Ceramic cartridge 40mm", "quantity": 2, "unitCost": 450.00 }
  ],
  "netDurationSeconds": 4200
}
```

Null fields are omitted:

- `providerId` is absent until a provider is assigned, and again after a Tenant assignment is declined.
- `address` is the saved address's label, and `coordinates` its point, both from customer-service's internal address lookup. Both are absent when it cannot be resolved; the default `CUSTOMER_CLIENT=stub` never resolves, compose sets `http`.
- `photos` (oldest first, `kind` `BEFORE` or `AFTER`, no URL because stored media is not served back) and `parts` are always present, possibly empty.
- `netDurationSeconds` appears once the job is complete.
- `tenantName` appears only while the booking is `PROVIDER_ASSIGNED` by a Tenant (see Tenants).

The app's type also allows `description`, `provider` and `invoice`, which booking-service never sends. The provider's name and rating belong to provider-service, the invoice belongs to invoice-service, and the description is not persisted.

**GET /internal/bookings/{bookingId}/payment-facts** answers 200 for any booking, 404 `BOOKING_NOT_FOUND` otherwise:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "c81f2a90-6d3e-4b57-a1c4-0e9f7b2d8a63",
  "status": "JOB_COMPLETED",
  "amount": 1250.00,
  "currency": "INR"
}
```

`amount` is the final total once set, otherwise the estimate, at scale 2.

**POST /internal/bookings/{bookingId}/payment-pending** takes `{"customerId": "..."}` (`@NotNull`) and answers the same body.

- It moves `JOB_COMPLETED` through `CUSTOMER_CONFIRMED` (audited, announced to no one) to `PAYMENT_PENDING`, or `CUSTOMER_CONFIRMED` to `PAYMENT_PENDING`, with the customer as the audit actor.
- A booking already `PAYMENT_PENDING` answers 200 unchanged, so payment-service can call it on every attempt.
- A missing booking, or one that is not this customer's, gives 404 `BOOKING_NOT_FOUND`. Any other state gives 409 `BOOKING_NOT_PAYABLE`.
- It retries a lost optimistic-lock race up to 3 times, then answers 409 `BOOKING_CHANGED`.

No event is published for these states. The booking is settled at `PAYMENT_COMPLETED` by the `PaymentCompleted` consumer (see Event contracts).

**POST /internal/bookings/{bookingId}/provider-accepted** takes `{"providerId": "..."}` (`@NotNull`).

- It records the provider, and the provider's Tenant when it has one (asked of provider-service, best effort).
- It walks the booking `SEARCHING_PROVIDER → PROVIDER_ASSIGNED → PROVIDER_ACCEPTED` in one transaction. The middle step is audited but not announced. The acceptance is announced by dispatch-engine's own `ProviderAccepted`.
- Repeating it for the same provider is a 200 no-op; a different provider gets 409.

**POST /internal/bookings/{bookingId}/searching-failed** takes no body.

- It acts only while the booking is still `SEARCHING_PROVIDER`.
- First it offers the booking to the Tenants that cover its address and category (customer-service address lookup, then provider-service `GET /internal/tenants/covering`). If any do, the booking enters `AWAITING_ASSIGNMENT` with no event (see Tenants).
- If none do, or either lookup fails, it moves to `SEARCHING_FAILED` and publishes `BookingCancelled`.
- The response is a `BookingResponse` whose `status` tells dispatch-engine which happened. A settled booking is returned unchanged.

**GET /internal/bookings/provider/{providerId}/active** returns `[{bookingId, reference, subcategoryId, status, emergency, scheduledAt, estimatedTotal}]`, soonest first. It covers the provider's bookings from `PROVIDER_ASSIGNED` through `PAYMENT_PENDING`, and `[]` when there are none.

**GET /internal/bookings/active?customerId=&addressId=** always answers 200 `{"bookingReference": "HFX-..."}`, with null when no active booking uses the address. "Active" is `SEARCHING_PROVIDER` through `CUSTOMER_APPROVAL_PENDING`. `CREATED` and `AWAITING_ASSIGNMENT` are not counted.

The full transition table is in [ARCHITECTURE.md](ARCHITECTURE.md) section 8.1. Statuses now include `AWAITING_ASSIGNMENT`, between `SEARCHING_PROVIDER` and `PROVIDER_ASSIGNED`, entered only through the Tenant fallback.

---

### catalog-service

Port 8085. `/catalog` is the public read surface; `/admin/catalog` is CRUD. Only `GET /catalog/**` is permitted anonymously; `CatalogRbacConfig` gates every `/admin/**` method (GET, POST, PUT, DELETE) to ADMIN, SUPER_ADMIN.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/catalog/categories` | public | Active categories with active subcategories, cached |
| GET | `/admin/catalog/categories` | ADMIN, SUPER_ADMIN | List all, including inactive |
| POST | `/admin/catalog/categories` | ADMIN, SUPER_ADMIN | Create a category, 201 |
| PUT | `/admin/catalog/categories/{categoryId}` | ADMIN, SUPER_ADMIN | Update a category |
| POST | `/admin/catalog/categories/{categoryId}/activate` | ADMIN, SUPER_ADMIN | Activate |
| POST | `/admin/catalog/categories/{categoryId}/deactivate` | ADMIN, SUPER_ADMIN | Deactivate |
| DELETE | `/admin/catalog/categories/{categoryId}` | ADMIN, SUPER_ADMIN | Delete with its subcategories, 204 |
| GET | `/admin/catalog/categories/{categoryId}/subcategories` | ADMIN, SUPER_ADMIN | List including inactive |
| POST | `/admin/catalog/categories/{categoryId}/subcategories` | ADMIN, SUPER_ADMIN | Create under an active parent, 201 |
| PUT | `/admin/catalog/subcategories/{subcategoryId}` | ADMIN, SUPER_ADMIN | Update |
| POST | `/admin/catalog/subcategories/{subcategoryId}/activate` | ADMIN, SUPER_ADMIN | Activate |
| POST | `/admin/catalog/subcategories/{subcategoryId}/deactivate` | ADMIN, SUPER_ADMIN | Deactivate |
| DELETE | `/admin/catalog/subcategories/{subcategoryId}` | ADMIN, SUPER_ADMIN | Delete, 204 |

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
  "details": ["activeProviderCount=2", "activeBookingCount=1", "booking=HFX-20260912-AB12CD"],
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
| POST | `/pricing/estimate` | CUSTOMER, SERVICE_PROVIDER, DISPATCHER, ADMIN, SUPER_ADMIN | Itemised breakdown for a subcategory |
| POST | `/pricing/overrides` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN | Validate a provider price against floor and ceiling |
| GET | `/admin/pricing/parameters/{subcategoryId}` | ADMIN, SUPER_ADMIN | Read parameters, cached |
| PUT | `/admin/pricing/parameters` | ADMIN, SUPER_ADMIN | Upsert parameters and invalidate the cache |
| GET | `/admin/pricing/config` | ADMIN, SUPER_ADMIN | Admin portal table of every configured subcategory |
| PUT | `/admin/pricing/config/{subcategoryId}` | ADMIN, SUPER_ADMIN | Admin portal merge edit of one subcategory |

Roles come from `PricingRbacConfig`, whose `/admin/**` rules cover GET, POST, PUT and DELETE.

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

Only `subcategoryId` is `@NotNull`. The numeric fields are optional and null is treated as zero. Note that `scheduledLocalTime` is a `LocalDateTime` with **no offset or trailing Z**; it is evaluated in the configured zone to decide the night window of 22:00 to 06:00 and the weekend window.

A non-blank `couponCode` is quoted by promotion-service's `GET /internal/coupons/{code}/quote`, with `X-Internal-Api-Key`, 1 s connect and 2 s read timeouts, and no retry. Outcomes:

- An unknown coupon gives 422 `COUPON_NOT_FOUND`.
- A failed constraint passes promotion's 422 code and message through: `COUPON_INACTIVE`, `COUPON_NOT_STARTED`, `COUPON_EXPIRED`, `MIN_ORDER_VALUE_NOT_MET`, `PER_USER_LIMIT_REACHED` or `TOTAL_LIMIT_REACHED`, or `COUPON_NOT_APPLICABLE` without a code.
- Any other failure gives 503 `COUPON_SERVICE_UNAVAILABLE`.

The per-user limit is checked only when `userId` is sent, and the discount is clamped to the pre-coupon total.

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

**GET /admin/pricing/config** returns up to 200 `PricingConfigDto`, most recently updated first. **PUT /admin/pricing/config/{subcategoryId}** takes the same shape and merges it: a null field keeps the stored value, and an absent subcategory is created. The answer is the merged result.

```json
{
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "subcategoryName": null,
  "categoryName": null,
  "basePrice": 349.00,
  "perKmRate": 10.00,
  "maxTravelCharge": 250.00,
  "platformFeePercent": 15.00,
  "nightSurcharge": 75.00,
  "weekendSurcharge": 50.00,
  "emergencyMultiplierCap": 1.50,
  "surgeMultiplierCap": 1.25,
  "currency": "INR"
}
```

`platformFeePercent` is a percentage (15 means the stored fraction 0.15). The two caps are the parameters' `emergencyMultiplier` and `surgeMultiplier`. `subcategoryName` and `categoryName` are always null and ignored on input, and `currency` is always `INR`. The tax rate and the override floor and ceiling are not in this shape and survive the merge. A body `subcategoryId` different from the path, or a merged result without `basePrice`, gives 400 `VALIDATION_ERROR`. No parameter is range-checked.

Parameters persist in Postgres (`pricing.pricing_parameters`) behind a read-through cache: in-process by default (`PRICING_CACHE=memory`, or `redis`), 60-second TTL, invalidated on write. A fresh database still needs `docker/seed-pricing.sh` before any estimate succeeds.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, a path and body id mismatch, or no `basePrice` after a merge |
| 404 | `PRICING_PARAMETERS_NOT_FOUND` | No parameters for the subcategory |
| 422 | `OVERRIDE_OUT_OF_RANGE` | Override outside floor and ceiling |
| 422 | `COUPON_NOT_FOUND`, promotion's constraint codes, `COUPON_NOT_APPLICABLE` | The coupon does not apply |
| 503 | `COUPON_SERVICE_UNAVAILABLE` | promotion-service could not be consulted |

---

### payment-service

Port 8088, controllers at `/payments` and `/admin/payments`. Roles come from `PaymentRbacConfig`; on top of them `CallerIdentity` checks ownership, where staff means `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `SUPPORT_AGENT` or `DISPATCHER`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/payments` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; the booking's customer, or staff paying on their behalf | Pay for a completed booking, idempotent per customer and booking |
| GET | `/payments/{transactionId}` | CUSTOMER, SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, FINANCE_ADMIN, SUPPORT_AGENT; the transaction's customer or staff | Read a transaction |
| POST | `/payments/callbacks/{transactionId}` | **public**, HMAC verified in the handler | Gateway webhook |
| POST | `/payments/{transactionId}/retries` | CUSTOMER, ADMIN, SUPER_ADMIN, FINANCE_ADMIN, SUPPORT_AGENT; the transaction's customer or staff | Record a customer-driven retry |
| POST | `/payments/{transactionId}/refunds` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Refund fully or partially |
| POST | `/payments/{transactionId}/refunds/{refundId}/reconcile` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Settle a refund stuck in `PENDING` |
| POST | `/payments/settlements` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Initiate a settlement transfer |
| GET | `/admin/payments?search=` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Admin portal list |
| POST | `/admin/payments/{id}/refund` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Admin portal refund, `Idempotency-Key` header |

A non-staff caller acting on another customer's transaction gets 403 `FORBIDDEN`. `SERVICE_PROVIDER` passes the role rule on the read, but the ownership check compares against the customer, so a provider always gets that 403.

**Payment idempotency is server-derived, not client-supplied.** For payments the key is built internally as `cust:<customerId>:booking:<bookingId>`, with `<customerId>` the booking's customer. It is reserved in Redis and backed by a unique column, so replaying the same booking returns the original transaction. A FAILED payment must not make a booking unpayable: when every earlier attempt is `FAILED`, a new request opens the next attempt under `...:attempt:<n>`, up to `max-payment-attempts` (default 10), after which the answer is 409 `PAYMENT_ATTEMPTS_EXHAUSTED`. Refunds take a client-supplied key instead (in the body on `/payments/{id}/refunds`, in the `Idempotency-Key` header on `/admin/payments/{id}/refund`), because two separate refunds of the same amount are legitimate and only the client knows whether a request is a retry.

**POST /payments**

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "method": "UPI",
  "gatewayId": "razorpay",
  "paymentCredential": "customer@okhdfcbank"
}
```

`bookingId` and `method` are `@NotNull`. `method` is one of `UPI`, `CREDIT_DEBIT_CARD`, `NET_BANKING`, `WALLET`, `CASH`. `gatewayId` is optional, at most 32 characters, and defaults to `homefix.payment.default-gateway` (env `PAYMENT_DEFAULT_GATEWAY`, default `razorpay`). An unknown gateway gives 400 `VALIDATION_ERROR`. `paymentCredential` is optional and stored encrypted, never returned. Older clients still send `customerId`, `providerId`, `amount` and `platformFee`. Those fields are accepted and ignored.

**Who pays, who is credited and how much come from booking-service**, never from the client. The steps:

1. `GET /internal/bookings/{bookingId}/payment-facts` on booking-service, with `X-Internal-Api-Key` (`INTERNAL_API_KEY`). A non-staff caller must be the booking's customer. Anyone else gets the same 404 `BOOKING_NOT_FOUND` as for an unknown booking.
2. If a live (non-FAILED) payment already exists for the booking it is returned, 201. This comes before the status check, so a duplicate request after a successful payment still gets its payment.
3. The booking must be `JOB_COMPLETED`, `CUSTOMER_CONFIRMED` or `PAYMENT_PENDING`, with a provider and a positive amount, else 409 `BOOKING_NOT_PAYABLE`.
4. `POST /internal/bookings/{bookingId}/payment-pending` moves the booking to `PAYMENT_PENDING` before any money moves. A 404 there gives `BOOKING_NOT_FOUND`, a 409 `BOOKING_NOT_PAYABLE`.
5. The charge is made with the booking's customer, provider and amount. The platform fee is always `default-platform-fee-percent` (20.00) of the amount, half-up to 2 decimals.

If booking-service cannot answer at any step (unreachable, 5xx, 401/403, open breaker, unreadable body), the request fails with 503 `BOOKING_SERVICE_UNAVAILABLE` and nothing is charged.

The PENDING transaction row is committed before the gateway is charged, and the charge request carries the transaction id so the gateway can echo it in its signed callback. A gateway decline marks the transaction `FAILED` and still answers 201 with that status. If the charge call itself errors the outcome is unknown, so the transaction stays `PENDING` for its callback to settle and the response is `502 PAYMENT_GATEWAY_ERROR`.

**The local `simulator` gateway.** It is registered only when `homefix.payment.gateways.simulator.enabled=true` (env `PAYMENT_SIMULATOR_ENABLED`, default `false`), and logs a WARN at startup. The local compose stack enables it and makes it the default gateway; Helm never does. Otherwise `simulator` is an unknown gateway (400). It accepts every charge, refund and transfer without moving money. After the charge commits it feeds itself a correctly signed `SUCCEEDED` callback (secret `PAYMENT_SIMULATOR_SECRET`, or a built-in dev default) through the normal callback path. So the `POST /payments` response is already `SUCCESS`. If that settlement fails the payment stays `PENDING`.

Response 201, and the same body with 200 from the read, callback, retry, refund and reconcile endpoints:

```json
{
  "id": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1250.00,
  "platformFee": 250.00,
  "providerNetEarning": 1000.00,
  "refundedAmount": 0.00,
  "method": "UPI",
  "gateway": "razorpay",
  "status": "PENDING",
  "attemptCount": 1,
  "createdAt": "2026-09-11T09:30:00Z",
  "updatedAt": "2026-09-11T09:30:00Z"
}
```

`providerNetEarning` is derived, not stored. Transitions are `PENDING → SUCCESS | FAILED`, then `SUCCESS → REFUNDED | PARTIALLY_REFUNDED`. `FAILED`, `REFUNDED` and `PARTIALLY_REFUNDED` are terminal.

**POST /payments/callbacks/{transactionId}**

```json
{
  "gatewayId": "razorpay",
  "payload": "{\"eventId\":\"evt_NqJ8xT2bVcL9Ae\",\"transactionId\":\"7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64\",\"gatewayId\":\"razorpay\",\"status\":\"SUCCEEDED\",\"amount\":\"1250.00\",\"timestamp\":\"2026-09-11T09:31:05Z\"}",
  "signature": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
}
```

The HMAC-SHA256 is recomputed over the UTF-8 bytes of the `payload` string and compared in constant time. The endpoint is public, so **everything the callback decides comes from inside the signed payload**; the outer body only carries the payload, its signature, and which gateway's secret to verify it with. The old unsigned `succeeded` and `failureReason` body fields have been removed. A body that still sends them is accepted, but they are ignored.

The signed `payload` is a JSON object:

| Field | Required | Rule |
|-------|----------|------|
| `eventId` | yes | Gateway event id, string, at most 128 characters. Stored on the transaction for reconciliation |
| `transactionId` | yes | Must equal `{transactionId}` in the path |
| `gatewayId` | yes | Must equal the body's `gatewayId` and the transaction's gateway |
| `status` | yes | `SUCCEEDED` or `FAILED`, exact upper case |
| `amount` | yes | Decimal in major units (rupees, not paise), as a JSON number or string. Must equal the transaction amount; scale is ignored, so `1250` matches `1250.00` |
| `timestamp` | yes | ISO-8601 instant. Rejected if older than `homefix.payment.callback-max-age` (default `72h`, `0` disables) or more than 5 minutes in the future |
| `failureReason` | no | Recorded only when `status` is `FAILED`, truncated to 512 characters |

Unknown payload fields are ignored, so a gateway may sign extra data. Checks run in this order, and nothing touches the database until the signature has passed:

| Check | Response |
|-------|----------|
| Unknown body `gatewayId` | 400 `VALIDATION_ERROR` |
| Signature does not verify | 400 `INVALID_CALLBACK_SIGNATURE`, logged as a security warning |
| Payload is not the JSON above | 400 `INVALID_CALLBACK_PAYLOAD` |
| Payload names another transaction than the path, or another gateway than the body | 400 `CALLBACK_MISMATCH`, logged as a security warning |
| Timestamp outside the window | 400 `CALLBACK_STALE` |
| Transaction does not exist | 404 `TRANSACTION_NOT_FOUND` |
| Payload gateway or amount differs from the transaction's | 400 `CALLBACK_MISMATCH`, logged as a security warning |
| Transaction already settled and the payload agrees (`SUCCEEDED` on `SUCCESS`, `PARTIALLY_REFUNDED` or `REFUNDED`, or `FAILED` on `FAILED`) | 200 with the current transaction. Idempotent no-op: no second event, invoice or wallet credit |
| Transaction already settled and the payload contradicts it | 409 `CALLBACK_CONFLICT` |

A gateway adapter must therefore send our transaction id to the gateway (it is in the charge request) and deliver callbacks in this shape. Real Razorpay and Stripe webhooks need translating into it by their adapters.

On success the `SUCCESS` state change, the `PaymentCompleted` outbox row and a wallet-credit-owed marker commit together. booking-service consumes the event and settles the booking at `PAYMENT_COMPLETED`. After that commit, with no transaction open, the handler does two things:

- It triggers invoice generation. The default trigger adapter only logs; the invoice is actually produced by invoice-service's `PaymentCompleted` consumer.
- It credits the provider through provider-service's `POST /internal/providers/{providerId}/earnings`, with `X-Internal-Api-Key` and body `{bookingId, bookingReference, gross, platformFee}`. `bookingReference` is always sent null. The credit is retried in line (3 attempts) and alerts finance on exhaustion. `WalletCreditSweeper` re-sends any credit still owed (every `PT1M`, for credits at least 5 minutes old). This happens only with `PROVIDER_WALLET_CLIENT=http`. The default `logging` client pays no one.

**POST /payments/{transactionId}/retries** takes **no body**; the optional reason is a query parameter:

```
POST /payments/7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64/retries?failureReason=UPI%20collect%20timed%20out
```

Only a `PENDING` transaction is retryable; anything else gives `NOT_RETRYABLE`. Once `attemptCount` reaches `max-customer-retries` (3) the transaction is moved to `FAILED` in the same call. This endpoint only increments a counter; it never re-charges the gateway. `attemptCount` is unrelated to the payment attempt number in the idempotency key.

**POST /payments/{transactionId}/refunds** takes `{"amount": 500.00, "idempotencyKey": "rf-7e4b1c93-1"}`. `amount` is `@NotNull`, at least 0.01, at most two decimals. `idempotencyKey` is required, client-chosen, at most 64 characters, and scoped to the transaction. **POST /admin/payments/{id}/refund** runs the same refund flow and takes `{"amount": 500.00, "reason": "..."}`. Here the key is in the `Idempotency-Key` header, and its absence gives 400 `IDEMPOTENCY_KEY_REQUIRED`. `reason` is `@NotBlank`, at most 500 characters, and is only logged with the acting staff member. The refund is legal only from `SUCCESS` and yields `PARTIALLY_REFUNDED` or `REFUNDED`. Because `PARTIALLY_REFUNDED` is terminal in the state machine, a transaction can be refunded only once.

The state and amount are validated, and a `PENDING` refund record is committed, before the gateway is asked to move any money. So an over-limit or illegal refund is rejected without any money moving. The gateway is then called with no transaction open, and the refund record id is passed to it as the gateway-side idempotency key.

| Situation | Response |
|-----------|----------|
| Amount exceeds what remains | 400 `VALIDATION_ERROR`, gateway not called |
| Transaction not refundable from its state | 409 `INVALID_STATE_TRANSITION`, gateway not called |
| No gateway charge reference recorded | 409 `GATEWAY_REFERENCE_MISSING`, gateway not called |
| Same key and amount, earlier attempt succeeded | 200 with the transaction, not refunded again |
| Same key and amount, earlier attempt rejected by the gateway | 502 `REFUND_GATEWAY_ERROR`, gateway not called again. Use a new key to retry |
| Same key and amount, earlier attempt still `PENDING` | Re-sent to the gateway under the same refund id, which the gateway de-duplicates; the outcome is recorded |
| A different key while another refund of this transaction is `PENDING` | 409 `REFUND_IN_PROGRESS` |
| Same key with a different amount | 409 `IDEMPOTENCY_KEY_REUSED` |
| Gateway explicitly rejects | 502 `REFUND_GATEWAY_ERROR`, the refund is recorded `FAILED` and Finance_Admin is alerted |
| Gateway call throws (timeout, reset) | 502 `REFUND_OUTCOME_UNKNOWN`, the refund stays `PENDING` and Finance_Admin is alerted. Retry with the same key, or reconcile |

**POST /payments/{transactionId}/refunds/{refundId}/reconcile** takes no body. It re-sends a `PENDING` refund under its original refund id and records the outcome. It answers 200 unchanged for a refund that already succeeded, 404 `REFUND_NOT_FOUND` for an unknown refund or one of another transaction, and 409 `REFUND_NOT_PENDING` for one that `FAILED`.

**GET /admin/payments?search=** returns a bare array, newest first, at most 200 rows. `search` is a case-insensitive substring of the transaction id, booking id or gateway reference.

```json
[
  {
    "id": "7e4b1c93-0d58-4a26-bf71-3c9e5d2a8b64",
    "bookingReference": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
    "customerName": null,
    "amount": 1250.00,
    "refundedAmount": 0.00,
    "currency": "INR",
    "method": "UPI",
    "status": "COMPLETED",
    "gateway": "simulator",
    "createdAt": "2026-09-11T09:30:00Z"
  }
]
```

This is the portal's vocabulary, not the transaction's:

- `bookingReference` carries the booking id, and `customerName` is always null.
- `method` is `UPI`, `CARD`, `NETBANKING`, `WALLET` or `CASH`.
- `status` is `PENDING`, `COMPLETED` (for `SUCCESS`), `FAILED`, `REFUNDED` or `PARTIALLY_REFUNDED`.

The admin refund answers the refunded transaction in this shape.

**POST /payments/settlements**

```json
{
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "amount": 1062.50,
  "bankAccountRef": "HDFC0001234/50100123456789",
  "gatewayId": "razorpay"
}
```

All four are `@NotNull`, and `amount` is at least 0.01 with at most two decimals. Response 201 is `{id, providerId, amount, status, requestedAt, updatedAt}` and never echoes the encrypted bank reference. Settlement statuses are `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, with transitions `PENDING → PROCESSING | FAILED` and `PROCESSING → COMPLETED | FAILED`.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, an unknown gateway, or the service money checks |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | Admin refund without an `Idempotency-Key` header |
| 400 | `INVALID_CALLBACK_SIGNATURE`, `INVALID_CALLBACK_PAYLOAD`, `CALLBACK_MISMATCH`, `CALLBACK_STALE` | Callback rejected; see the callback checks |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 403 | `FORBIDDEN` | Not the transaction's customer and not staff |
| 404 | `TRANSACTION_NOT_FOUND` | Unknown transaction |
| 404 | `BOOKING_NOT_FOUND` | Unknown booking, or not the caller's |
| 404 | `REFUND_NOT_FOUND` | Reconcile of an unknown refund |
| 409 | `BOOKING_NOT_PAYABLE` | Booking not in a payable state, or no provider or amount |
| 409 | `PAYMENT_ATTEMPTS_EXHAUSTED` | Every one of the `max-payment-attempts` attempts failed |
| 409 | `INVALID_STATE_TRANSITION` | Refund or status change not permitted from the current status |
| 409 | `NOT_RETRYABLE` | Retry attempted on a non-pending transaction |
| 409 | `CALLBACK_CONFLICT` | Callback contradicts the settled outcome |
| 409 | `REFUND_IN_PROGRESS`, `IDEMPOTENCY_KEY_REUSED`, `GATEWAY_REFERENCE_MISSING`, `REFUND_NOT_PENDING` | See the refund rules |
| 502 | `PAYMENT_GATEWAY_ERROR` | The charge call errored; the transaction stays `PENDING` |
| 502 | `REFUND_GATEWAY_ERROR` | The gateway rejected the refund |
| 502 | `REFUND_OUTCOME_UNKNOWN` | The gateway refund call threw; the refund stays `PENDING` |
| 503 | `BOOKING_SERVICE_UNAVAILABLE` | booking-service could not be consulted; nothing was charged |

The handler maps only `PaymentException` and bean-validation failures. An unreadable body, for example an unknown `method`, gets Spring's default error body.

---

### invoice-service

Port 8089, read-only controller at `/invoices`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/invoices/customers/{customerId}?page=&size=` | CUSTOMER, ADMIN, SUPER_ADMIN, FINANCE_ADMIN, SUPPORT_AGENT; owner or staff | Invoice history within the 24-month retention window |
| GET | `/invoices/providers/{providerId}/statements/{year}/{month}` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, FINANCE_ADMIN, SUPPORT_AGENT; owner or staff | Monthly earnings statement |

`page` defaults to 0 and a negative value becomes 0. `size` defaults to 20 and is capped at 100. Year and month are plain int path variables with no range validation, so an invalid month surfaces as a 500.

`GlobalExceptionHandler` uses the shared envelope. The reachable codes are 401 `UNAUTHENTICATED` / `INVALID_PRINCIPAL`, 403 `FORBIDDEN` (path id is not the caller and the caller is not staff) and 400 `VALIDATION_ERROR`.

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
  "available": true
}
```

`available` stays false until the first day of the following month, and while it is false the count and the three amounts are zero. The statement returns 200 either way; unavailability is a flag, not a status code.

---

### promotion-service

Port 8098 locally, controllers at `/coupons`, `/admin/coupons` and `/internal/coupons`. Roles come from `PromotionRbacConfig`. Validate, redeem and cancel also require the body's `userId` to be the caller unless the caller is staff, otherwise 403 `FORBIDDEN`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/coupons` | ADMIN, SUPER_ADMIN | Create a coupon |
| GET | `/coupons/{id}` | CUSTOMER, SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Read by id |
| GET | `/coupons/code/{code}` | same | Read by code, case-insensitive |
| POST | `/coupons/{id}/activate` | ADMIN, SUPER_ADMIN | Re-enable |
| POST | `/coupons/{id}/deactivate` | ADMIN, SUPER_ADMIN | Stop further redemptions |
| POST | `/coupons/validate` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT; own `userId` or staff | Checkout validation; moves no counters |
| POST | `/coupons/redeem` | same | Atomically advance both counters |
| POST | `/coupons/cancel` | same | Atomically reverse both counters |
| GET | `/admin/coupons` | ADMIN, SUPER_ADMIN | Admin portal list, newest first, at most 200 |
| POST | `/admin/coupons` | ADMIN, SUPER_ADMIN | Create, same body and rules as `POST /coupons`, 201 |
| PATCH | `/admin/coupons/{id}/deactivate` | ADMIN, SUPER_ADMIN | Deactivate |
| GET | `/internal/coupons/{code}/quote?orderValue=&userId=` | service credential | Read-only quote for pricing-engine |

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

| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 403 | `FORBIDDEN` | Body `userId` is not the caller and the caller is not staff |
| 401 | `INTERNAL_AUTH_FAILED` | `/internal/**` without the right key (filter body) |

The admin endpoints answer `AdminCouponResponse`. It carries `{id, code, discountType, discountValue, minOrderValue, maxDiscountCap, validFrom, expiryDate, perUserLimit, totalLimit, totalRedeemed, status}`, where `maxDiscountCap` is omitted when null. `status` is `ACTIVE`, `INACTIVE` or `EXPIRED`, derived on the service clock: past expiry is always `EXPIRED`, and a coupon that has not started yet is `ACTIVE`.

**GET /internal/coupons/{code}/quote** runs the same read-only validation as `/coupons/validate` and answers `{couponId, code, discountAmount}`. `userId` is optional, and the per-user limit is skipped without it. A negative `orderValue` gives 400 `VALIDATION_ERROR`. Pricing-engine now passes these codes through unchanged, so the two services no longer spell the same violation differently.

---

### complaint-service

Port 8091, controllers at `/complaints` and `/admin/complaints`. The `customerId` is always the JWT subject, never the body. Roles come from `ComplaintRbacConfig`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/complaints` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Raise a complaint against a booking |
| POST | `/complaints/{complaintId}/status` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Change status and notify the customer |
| POST | `/complaints/{complaintId}/refund` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Approve a refund (at most one per complaint), 202 with the recorded refund |
| POST | `/complaints/{complaintId}/dispute` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Set disputed and hold the provider settlement |
| GET | `/complaints/stats` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Aggregated statistics |
| GET | `/admin/complaints?search=&status=` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Admin portal list, newest first, at most 200 |
| PATCH | `/admin/complaints/{complaintId}` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Change status and/or record a resolution note |

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

The status change takes `{"status": "IN_PROGRESS"}`. The dispute endpoint takes no body and sets the settlement hold.

**POST /complaints/{complaintId}/refund**

```json
{
  "amount": 1250.00,
  "reason": "Tap leaked again within two hours of the repair.",
  "idempotencyKey": "agent-7f3c-refund-1"
}
```

`amount` is required, positive, with at most two decimal places. `reason` (up to 500 characters) and `idempotencyKey` (up to 64) are optional. The approving agent is the JWT subject, never the body.

A complaint is refunded **at most once**, whatever the outcome of the first attempt; a failed refund goes to Finance_Admin for manual processing. Only a complaint in `OPEN`, `IN_PROGRESS`, `ESCALATED` or `DISPUTED` can be refunded. `RESOLVED`, `CLOSED` and `REFUND_FAILED` are refused. The refund is recorded as `PENDING` and committed before the Payment Service is called. The call runs outside any database transaction and carries a stable idempotency key derived from the record id (`complaint-refund:<refund id>`). The outcome is recorded in a second transaction. This service does not cap the amount; the Payment Service checks it against the transaction.

Response 202 with the recorded refund. A rejection is not an error: the body has `"status": "FAILED"` and a `failureReason`, the complaint moves to `REFUND_FAILED`, the customer is notified and Finance_Admin is alerted.

```json
{
  "id": "5c0f6a2e-8d1b-4f7a-9e3c-2b4d6f8a1c90",
  "complaintId": "e81c4f27-3a69-4b05-9d82-7f1e6c3a5b94",
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "amount": 1250.00,
  "reason": "Tap leaked again within two hours of the repair.",
  "approvedBy": "3a351d47-7395-4902-85d5-3c354dcb2ee7",
  "status": "SUCCEEDED",
  "externalReference": "stub_rf_0b6f2a6c-3e1d-3c55-9a8e-4f1b7d2c9e01",
  "failureReason": null,
  "requestedAt": "2026-09-12T10:15:00Z",
  "completedAt": "2026-09-12T10:15:01Z"
}
```

A retry with the same `idempotencyKey` and amount returns the recorded refund again without calling the Payment Service. Any other second request is refused with `REFUND_ALREADY_REQUESTED`. The default refund adapter is a logging stub that approves without moving money; there is no HTTP adapter to payment-service yet.

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
| 409 | `REFUND_NOT_ALLOWED` | Refund of a complaint that is `RESOLVED`, `CLOSED` or `REFUND_FAILED` |
| 409 | `REFUND_ALREADY_REQUESTED` | The complaint already has a refund and the request is not a retry of it |
| 409 | `REFUND_IN_PROGRESS` | Retry of a refund whose outcome is not recorded yet |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Retry with the same `idempotencyKey` but a different amount |
| 502 | `REFUND_OUTCOME_UNKNOWN` | The Payment Service call failed; the refund stays `PENDING` and Finance_Admin is alerted to reconcile it |
| 503 | `NO_AGENT_AVAILABLE` | No support agent could be assigned |

Repeated refund calls no longer refund repeatedly (review sections 8.5 and 12.3 item 4); see the refund rules above.

**GET /admin/complaints?search=&status=** returns a bare array of `{id, bookingReference, raisedByName, category, summary, status, slaDueAt, createdAt}`.

- `bookingReference` and `raisedByName` are always null.
- `summary` is the description, and `slaDueAt` the SLA deadline.
- `search` is a case-insensitive substring of the description or of the complaint, booking or customer id.
- `status` is one complaint status.

**PATCH /admin/complaints/{complaintId}** takes `{"status": "RESOLVED", "resolutionNote": "..."}`. `status` is `@NotNull`. A note is required for `RESOLVED`, at most 2000 characters, else 400 `VALIDATION_ERROR`. A blank note counts as none, and a new note replaces the previous one. A status different from the current one goes through the normal status change, with the same transitions, settlement hold, event and notification. Re-sending the current status only records the note. `ESCALATED` and `REFUND_FAILED` are system-only and give 409 `INVALID_COMPLAINT_TRANSITION`. The note is stored but not returned by any response.

---

### location-service

Port 8092 locally, controller at `/locations`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/locations/{bookingId}` | SERVICE_PROVIDER | Ingest a GPS ping, return the recomputed ETA, 202 |
| GET | `/locations/{bookingId}` | CUSTOMER, SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT, DISPATCHER | Current tracking view |
| GET | `/locations/{bookingId}/stream` | same | SSE stream of live pushes |

Roles come from `LocationRbacConfig` (`POST /locations/**` and `GET /locations/**`).

```json
{
  "latitude": 12.971599,
  "longitude": 77.594566
}
```

Both are `@NotNull`, with latitude in [-90, 90] and longitude in [-180, 180], re-checked in the domain type. **The provider id is the JWT subject.** A `providerId` in the body is accepted and ignored, and a non-UUID subject gets a 401 with Spring's default body. There is still no ownership check. Any `SERVICE_PROVIDER` can post pings for any booking, not only the provider assigned to it. Any of the reading roles can read or stream any booking's position.

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
| POST | `/chat/channels/{bookingId}/messages` | CUSTOMER, SERVICE_PROVIDER; participant check enforced | Send a message, 201 |
| GET | `/chat/channels/{bookingId}/messages` | CUSTOMER, SERVICE_PROVIDER; participant check enforced | Full history, oldest first |

`ChatService` compares the principal against the channel's stored customer and provider ids and throws `CHANNEL_ACCESS_FORBIDDEN` otherwise, and `WebSocketAuthorizationConfig` applies the same rule to STOMP `SUBSCRIBE`. Staff and Tenant admins are refused by the role rule before the participant check runs. The gap is that the interceptor only inspects `SUBSCRIBE`, so a connected client can `SEND` straight to `/topic/chat/{bookingId}` and bypass the participant check, the masking and the persistence.

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

A channel is opened by `ProviderAccepted`, which now carries `customerId` from both of its producers. A `PaymentCompleted` or `BookingCancelled` for a booking with no channel writes a deactivated tombstone, and a later `ProviderAccepted` does not reopen it. So a booking cancelled before acceptance answers its customer 200 with an empty history and 409 `CHANNEL_DEACTIVATED` on send, rather than 404.

---

### rating-review-service

Port 8097 locally, controller at `/reviews`. The reviewer is the JWT subject. The client IP for fraud detection is the first hop of `X-Forwarded-For`, which a client can set freely.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/reviews` | CUSTOMER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Customer reviews provider |
| POST | `/reviews/customer` | SERVICE_PROVIDER, ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Provider reviews customer |
| POST | `/reviews/{reviewId}/approval` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Approve a flagged review back into the aggregate |
| POST | `/reviews/{reviewId}/removal` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Remove a review and recalculate, 204 |
| GET | `/admin/reviews?status=` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Admin portal list, newest first, at most 200 |
| POST | `/admin/reviews/{reviewId}/moderate` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT | Publish or remove a review |

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

The submission path requires the caller to be the reviewer the prompt invited, otherwise 403 `NOT_INVITED_REVIEWER`, and persists the caller's id.

**GET /admin/reviews?status=** returns a bare array of `{id, bookingReference, reviewerName, providerName, rating, comment, status, createdAt}`.

- `bookingReference`, `reviewerName` and `providerName` are always null.
- `rating` is the overall score, and `comment` the review text.
- `status` (also the filter) is `PENDING`, `FLAGGED`, `PUBLISHED` or `REMOVED`, derived from the active and flagged flags. `PENDING` always lists nothing.

**POST /admin/reviews/{reviewId}/moderate** takes `{"action": "PUBLISH" | "REMOVE", "reason": "..."}` (`reason` at most 1000) and answers the review as it now stands.

- `REMOVE` requires a reason (400 `VALIDATION_ERROR`), is audited with the acting staff member, and recalculates the aggregate.
- `PUBLISH` of a removed review gives 409 `REVIEW_REMOVED`.
- Repeating either is a no-op.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` | Bean validation, or a blank removal reason |
| 401 | `UNAUTHENTICATED` / `INVALID_PRINCIPAL` | No principal, or a non-UUID subject |
| 404 | `REVIEW_NOT_FOUND` | Unknown review, or no open prompt for the booking |
| 409 | `REVIEW_WINDOW_CLOSED` | The 7-day window has closed |
| 409 | `DUPLICATE_REVIEW` | A review already exists for that booking and role |
| 403 | `NOT_INVITED_REVIEWER` | The caller is not the reviewer the prompt invited |
| 409 | `REVIEW_REMOVED` | Publishing a removed review |

---

### dispatch-engine

Port 8087. The gateway routes `/dispatch/offers/**` (providers answering offers) and `/admin/dispatch/**` (matching settings) here. Roles come from `DispatchRbacConfig`, in this order:

| Rule | Roles |
|------|-------|
| `GET /dispatch/offers/**`, `POST /dispatch/offers/**` | SERVICE_PROVIDER |
| `PUT /admin/dispatch/**` | SUPER_ADMIN |
| `GET /admin/dispatch/config` | ADMIN, SUPER_ADMIN, DISPATCHER |
| `GET /admin/dispatch/**` | ADMIN, SUPER_ADMIN |

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/dispatch/offers` | SERVICE_PROVIDER | The caller's pending offers, soonest-expiring first |
| GET | `/dispatch/offers/{bookingId}` | SERVICE_PROVIDER; the offer's provider | One offer, any status |
| POST | `/dispatch/offers/{bookingId}/accept` | same | Accept the offer |
| POST | `/dispatch/offers/{bookingId}/decline` | same | Decline the offer |
| GET | `/admin/dispatch/weights` | ADMIN, SUPER_ADMIN | Read the matching weights |
| PUT | `/admin/dispatch/weights` | SUPER_ADMIN | Replace them, validated before the store changes |
| GET | `/admin/dispatch/config` | ADMIN, SUPER_ADMIN, DISPATCHER | Weights plus radius, expansion and offer timeout |
| PUT | `/admin/dispatch/config` | SUPER_ADMIN | Replace all of those, all-or-nothing |

**Offers.** An offer belongs to one provider; anyone else gets 404 `OFFER_NOT_FOUND`. Offers live in Redis, so every replica sees them. Response:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "status": "PENDING",
  "reference": "HFX-20260912-AB12CD",
  "emergency": false,
  "subcategoryId": "7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77",
  "scheduledAt": "2026-09-12T10:00:00Z",
  "offeredAt": "2026-09-11T09:30:00Z",
  "expiresAt": "2026-09-11T09:31:00Z",
  "expiresInSeconds": 42,
  "timeoutSeconds": 60
}
```

`status` is `PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED` or `WITHDRAWN` (the booking was cancelled). Answering an expired offer gives 409 `OFFER_EXPIRED`, and answering a decided one gives 409 `OFFER_ALREADY_DECIDED`. The offer is not pushed: providers poll `GET /dispatch/offers`, because the notification call it would make has no endpoint (see below).

**Weights.**

```json
{
  "distanceWeight": 0.30,
  "availabilityWeight": 0.25,
  "ratingWeight": 0.20,
  "skillWeight": 0.15,
  "performanceWeight": 0.10
}
```

All five are `@NotNull` and must sum to exactly 1.0, checked with `BigDecimal` before the store is touched, so a rejected update leaves the existing weights intact. The same shape is the response.

**Config.** It takes and returns `{weights: {distance, availability, rating, skill, performance}, initialRadiusKm, radiusIncrementKm, maxExpansionCycles, offerTimeoutSeconds}`. Every field is `@NotNull`. The rules are:

- the weights sum to 1.0;
- `initialRadiusKm` > 0;
- `radiusIncrementKm` ≥ 0;
- `maxExpansionCycles` 0 to 10;
- `offerTimeoutSeconds` 15 to 600.

A PUT changes nothing unless every value passes. Weights and settings are held in process memory, so changes are lost on restart and differ between replicas.

This service has no `@ControllerAdvice`. Each controller has a local handler that uses the shared envelope without a `correlationId`:

- weights and config: 400 `INVALID_DISPATCH_WEIGHTS`;
- config only: 400 `INVALID_DISPATCH_SETTINGS` and 400 `VALIDATION_ERROR`;
- offers: 401 `UNAUTHENTICATED` / `INVALID_PRINCIPAL`, 404 `OFFER_NOT_FOUND`, 409 `OFFER_EXPIRED`, 409 `OFFER_ALREADY_DECIDED`.

A `@NotNull` violation on the weights endpoint still returns Spring's default body.

**Outbound calls.** All are synchronous, under the shared resilience stack. Every internal call sends `X-Internal-Api-Key` (`INTERNAL_API_KEY`), and the service refuses to start without one.

| Call | Purpose | Credential |
|------|---------|------------|
| customer-service `GET /internal/addresses/{addressId}` (`CUSTOMER_SERVICE_URL`) | Address to coordinates while consuming `BookingCreated`; the returned `customerId` must equal the booking's | internal key |
| catalog-service `GET /catalog/categories` (`CATALOG_SERVICE_URL`) | Subcategory to `skillTags`, from the public active listing | none |
| provider-service `GET /internal/providers/eligible` (`PROVIDER_SERVICE_URL`) | Candidates for each radius cycle. Any failure, including 401/403, is read as "no providers" | internal key |
| booking-service `POST /internal/bookings/{id}/provider-accepted` `{providerId}` (`BOOKING_SERVICE_URL`) | An offer was accepted. 404 or 409 stops the search | internal key |
| booking-service `POST /internal/bookings/{id}/searching-failed` | Every cycle exhausted, or the booking cannot be resolved. The answer's `status` says whether booking-service failed it (`SEARCHING_FAILED`) or queued it for Tenants (`AWAITING_ASSIGNMENT`) | internal key |
| notification-service `POST /internal/notifications/job-offer`, `/no-provider-available`, `/dispatcher-alert` | Offer push, and the "no provider" and dispatcher notices, sent only for `SEARCHING_FAILED` | none. notification-service has no such endpoint, so these are best-effort no-ops today |

After an accepted offer, dispatch-engine writes its own `ProviderAccepted` to the outbox. It also consumes `BookingCancelled` (group `dispatch-engine.booking-cancelled`), reading only `bookingId`. It flags the booking so no further offer is made and no rejection is announced, and withdraws a pending offer. See `BookingCreated` below for how each enrichment outcome is handled.

---

### admin-service

Port 8095. It now serves only what reaches it through the gateway's `/admin/**` catch-all: the dashboard, audit logs, system configuration and a verification-queue stub. Every other admin portal module is served by the service that owns its data. See the api-gateway route table and each service's `/admin/...` rows: users in auth-service, providers and tenants in provider-service, bookings, payments, complaints, coupons, reviews, reports, notification templates, dispatch, catalog, pricing, verification. Old module paths such as `/admin/categories` land here and get 404.

Role enforcement has two layers. `AdminRbacConfig` fills the shared rule map, most specific first:

- every method on `/admin/system-config/**` requires `SUPER_ADMIN`;
- `GET /admin/audit-logs/**` requires `ADMIN` or `SUPER_ADMIN`;
- every method on `/admin/**` requires `ADMIN` or `SUPER_ADMIN`.

Then `AdminAuthorization` re-checks per module. `SUPER_ADMIN` passes everything, and `ADMIN` passes everything except `SYSTEM_CONFIGURATION`. Its denial, 403 `MODULE_ACCESS_DENIED` in the shared envelope, is defence in depth: the filter's `SUPER_ADMIN` rule refuses an `ADMIN` first, with its bare `{"error": "..."}` body. The fifteen-module enum is unchanged, although most modules are no longer served here.

| Method | Path | Module | Purpose |
|--------|------|--------|---------|
| GET | `/admin/dashboard` | none checked | Operational metrics |
| GET | `/admin/audit-logs?action=&entityType=&cursor=` | `AUDIT_LOGS` | Paged audit view for the portal |
| GET | `/admin/audit-logs?entityType=&entityId=` | `AUDIT_LOGS` | Raw trail for one entity |
| GET | `/admin/audit-logs?actorId=` | `AUDIT_LOGS` | Raw trail for one actor |
| GET | `/admin/system-config` | `SYSTEM_CONFIGURATION` | Every setting, super admin only |
| PUT | `/admin/system-config` | `SYSTEM_CONFIGURATION` | Update several settings at once, audited, super admin only |
| PUT | `/admin/system-config/{key}` | `SYSTEM_CONFIGURATION` | Set one setting, audited, super admin only |
| GET | `/admin/verification-queue` | `VERIFICATION_QUEUE` | Stub that always answers `[]`; the real queue is verification-service's `/admin/verification/queue` |

**GET /admin/dashboard**

```json
{
  "activeBookings": 0,
  "activeProvidersOnline": 0,
  "newRegistrationsLast24h": 0,
  "grossRevenueLast24h": 0,
  "avgProviderResponseTimeSeconds": 0.0,
  "openComplaintCount": 0,
  "platformRating": 0.0,
  "refreshedAt": "2026-09-11T09:30:00Z"
}
```

The snapshot is refreshed every 60 seconds from a stub source, so every metric is zero today.

**GET /admin/audit-logs** with neither `entityType`+`entityId` nor `actorId` is the paged view:

```json
{
  "entries": [
    {
      "id": "a93c5e71-2d48-4b06-8f15-6c7e9a3d1b82",
      "actorId": "339a9dc7-1e33-4acb-8549-a3916ae3bcef",
      "actorName": null,
      "action": "UPDATE",
      "entityType": "SYSTEM_CONFIG",
      "entityId": "audit.pageSize",
      "changeSummary": "audit.pageSize: 50 → 100",
      "timestamp": "2026-09-11T09:30:00Z"
    }
  ],
  "nextCursor": "MjAyNi0wOS0xMVQwOTozMDowMFp8YTkzYzVlNzE"
}
```

- `action` (also the filter, case-insensitive) is `CREATE`, `UPDATE`, `DELETE`, `APPROVE` or `REJECT`.
- `entityType` filters by a case-insensitive substring.
- `cursor` is opaque, for keyset paging newest first, and `nextCursor` is omitted on the last page.
- The page size is the `audit.pageSize` setting.
- `changeSummary` is at most 300 characters. It reads `field: old → new`, or just the changed field names when `audit.summaryShowsValues` is false.
- `actorName` is always null.
- An unknown `action` or a malformed cursor gives 400 `INVALID_AUDIT_QUERY`.

The by-entity and by-actor forms still return the JPA entity directly, with the before and after states as **JSON-encoded strings**:

```json
[
  {
    "id": "a93c5e71-2d48-4b06-8f15-6c7e9a3d1b82",
    "actorId": "339a9dc7-1e33-4acb-8549-a3916ae3bcef",
    "actionType": "UPDATE",
    "entityType": "SYSTEM_CONFIG",
    "entityId": "audit.pageSize",
    "beforeValues": "{\"audit.pageSize\":\"50\"}",
    "afterValues": "{\"audit.pageSize\":\"100\"}",
    "loggedAt": "2026-09-11T09:30:00Z"
  }
]
```

The only writer of audit entries in this service today is system configuration.

**System configuration.** Two settings exist, defined in code with their defaults and rules: `audit.pageSize` (NUMBER, default 50, 10 to 200) and `audit.summaryShowsValues` (BOOLEAN, default true). A value is persisted in `admin.system_setting` only once it differs from its default, so changes survive restarts. All three endpoints answer an array:

```json
[
  {
    "key": "audit.pageSize",
    "label": "Audit log page size",
    "description": "Number of entries per page in the Audit Logs view.",
    "type": "NUMBER",
    "value": "50"
  }
]
```

`PUT /admin/system-config` takes `{"updates": {"audit.pageSize": "100"}}`, and `PUT /admin/system-config/{key}` takes `{"value": "100"}`.

- Values are normalised (trimmed, `"0050"` becomes `"50"`, `TRUE` becomes `true`).
- An update is all-or-nothing, in one transaction with its audit entries.
- An unchanged value is neither written nor audited, and each changed key gets one `UPDATE` entry.

| HTTP | errorCode | When |
|------|-----------|------|
| 400 | `INVALID_SYSTEM_CONFIG` | `details` lists every problem: an unknown key, a value out of range, or a missing `updates` |
| 400 | `INVALID_AUDIT_QUERY` | Unknown `action`, or a malformed `cursor` |
| 400 | `VALIDATION_ERROR` | Bean validation, or a missing or malformed body |
| 403 | `MODULE_ACCESS_DENIED` | An `ADMIN` reaching `SYSTEM_CONFIGURATION`; in practice the filter answers first |
| 401, 403 | bare `{"error":"..."}` | Rejected by the RBAC filter before the controller |

A non-UUID `actorId` has no handler and gets Spring's default 400 body.

---

### reporting-service

Port 8096, controllers at `/reports` and `/admin/reports`. `ReportingRbacConfig` gates GET and POST on both prefixes to `ADMIN`, `SUPER_ADMIN` or `FINANCE_ADMIN`. `ReportAuthorization` then requires `FINANCE_ADMIN` specifically for finance-restricted report types.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/reports` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN, plus a per-type check | Generate inline, 200, or accept for async delivery, 202 |
| POST | `/reports/export` | same | Same, returning a CSV or PDF attachment |
| GET | `/admin/reports/types` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN | Report types the caller may run |
| POST | `/admin/reports` | same, plus the per-type check | Admin portal adapter over `POST /reports`: 200 `SYNC` or 202 `QUEUED` |
| POST | `/admin/reports/export` | same | Same as `/reports/export` |

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
  "message": null
}
```

`message` carries the "no data" text when nothing matched, and is otherwise null.

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
**Admin portal adapter.**

- `GET /admin/reports/types` returns `[{id, name, financeOnly}]`, for example `{"id": "SETTLEMENT", "name": "Settlement Report", "financeOnly": true}`. The finance types are listed only for `FINANCE_ADMIN`.
- `POST /admin/reports` and `/admin/reports/export` take `{reportTypeId, fromDate, toDate, format}`. All four are required, `reportTypeId` must be a constant above exactly, and there are no dimension filters.
- The report answer is `{mode: "SYNC" | "QUEUED", message}`, omitting nulls. On `SYNC`, `message` is the no-data text or `"Report ready: N rows."`; on `QUEUED`, the email-delivery text. `downloadUrl` is declared but never set.
- The export answers like `/reports/export`.

| HTTP | errorCode | When |
|------|-----------|------|
| 403 | `REPORT_ACCESS_DENIED` | A finance-restricted type without `FINANCE_ADMIN` |
| 400 | `INVALID_REPORT_REQUEST` | `to` before `from`, a null required filter, or an unknown `reportTypeId` |
| 400 | `VALIDATION_ERROR` | Bean validation |

---

### Tenants (provider-service and booking-service)

Service agencies inside the marketplace. Full design: `.kiro/specs/multi-tenant-and-mobile/design.md`.
Gateway routes: `/admin/tenants/**` and `/tenant/**` → provider-service; `/tenant/bookings/**` →
booking-service (declared above `/tenant/**`). The caller's Tenant is always resolved from the token's
subject; nothing in a request names a Tenant. provider-service reads it on every call. booking-service
caches the answer, including "not a Tenant admin", for 60 seconds per user, so adding or removing an
admin, or suspending a Tenant, can take up to a minute to reach `/tenant/bookings/**`.

**Platform admin** (ADMIN, SUPER_ADMIN; provider-service)

| Method & path | Body | Answer |
|---|---|---|
| `GET /admin/tenants` | — | `Tenant[]`, by name |
| `POST /admin/tenants` | `{name, contactPhone?, contactEmail?, baseLatitude, baseLongitude, serviceRadiusKm, categoryIds[]}` | 201 `Tenant` |
| `PUT /admin/tenants/{id}` | same + optional `status` (`ACTIVE`/`SUSPENDED`, omitted keeps it) | `Tenant` |
| `GET /admin/tenants/{id}/members` | — | `{admins:[{userId, mobileNumber}], providers: TeamProvider[]}` |
| `POST /admin/tenants/{id}/admins` | `{mobileNumber}` | 201 `{userId, mobileNumber}` (grants `TENANT_ADMIN` in auth-service; re-adding is idempotent) |
| `DELETE /admin/tenants/{id}/admins/{userId}` | — | 204 (revokes the role and ends the user's refresh sessions; access tokens keep the role until they expire) |
| `POST /admin/tenants/{id}/providers` | `{mobileNumber}` | 201 `TeamProvider` (a provider already on the team is answered unchanged) |
| `DELETE /admin/tenants/{id}/providers/{providerId}` | — | 204 |
| `GET /admin/tenants?status=PENDING_APPROVAL` | — | `Tenant[]` with that status (any status works) |
| `POST /admin/tenants/{id}/approval` | — | `Tenant` (`ACTIVE`; the applicant becomes its admin and is emailed); 409 `APPLICATION_NOT_PENDING` |
| `POST /admin/tenants/{id}/rejection` | `{reason}` (1–500) | `Tenant` (`REJECTED`; the applicant is emailed the reason); 400 `REASON_REQUIRED`; 409 `APPLICATION_NOT_PENDING` |

**Agency applications** (any signed-in user; gateway route `/tenant-applications/**` → provider-service)

| Method & path | Body | Answer |
|---|---|---|
| `POST /tenant-applications` | the Tenant body | 201 `{tenantId, name, status:"PENDING_APPROVAL", rejectionReason, createdAt}`; 409 `APPLICATION_EXISTS` (one pending or active application per user, or already an admin) |
| `GET /tenant-applications/me` | — | the caller's latest application; 404 `APPLICATION_NOT_FOUND` |

A `PENDING_APPROVAL` or `REJECTED` Tenant covers no booking and grants no `TENANT_ADMIN`; its status changes
only by approval or rejection (`PUT` and adding admins answer 409 `APPLICATION_NOT_DECIDED`).

`Tenant = {id, name, status, contactPhone, contactEmail, baseLatitude, baseLongitude, serviceRadiusKm,
categoryIds[], providerCount, adminCount, createdAt, updatedAt, applicantUserId, rejectionReason}`. The Tenant body has no bean validation;
the service checks it and answers named 400 codes:

- name 2–120 characters after trimming (`INVALID_TENANT_NAME`);
- optional `contactPhone` `[+0-9 ()-]{4,20}` (`INVALID_CONTACT_PHONE`) and `contactEmail` up to 254 (`INVALID_CONTACT_EMAIL`);
- latitude −90..90 and longitude −180..180, both required (`INVALID_LATITUDE`, `INVALID_LONGITUDE`);
- radius 1–100 km (`INVALID_SERVICE_RADIUS`);
- at least one category (`CATEGORIES_REQUIRED`, `INACTIVE_CATEGORY`);
- a bad `status` (`INVALID_TENANT_STATUS`).

`{mobileNumber}` is `@NotBlank`, at most 32 characters, and spaces, `-` and brackets are stripped before the lookup. In member lists an admin's `mobileNumber` is null when auth-service cannot answer.

**Tenant admin** (TENANT_ADMIN)

| Method & path | Service | Answer |
|---|---|---|
| `GET /tenant/me` | provider | `Tenant` |
| `GET /tenant/providers` | provider | `TeamProvider[]` = `{providerId, displayName, mobileNumber, primarySkill, verificationStatus, rating, availableNow, assignable}` |
| `POST /tenant/providers {mobileNumber}` / `DELETE /tenant/providers/{id}` | provider | 201 `TeamProvider` / 204 |
| `GET /tenant/bookings/queue` | booking | `TenantBooking[]`, oldest queued first, ≤ 200 |
| `GET /tenant/bookings?status=` | booking | `TenantBooking[]` of the Tenant's jobs, newest first, ≤ 200 |
| `POST /tenant/bookings/{key}/assignment {providerId}` | booking | `TenantBooking` (booking → `PROVIDER_ASSIGNED`) |

`TeamProvider` details:

- `mobileNumber` is null in lists and set only in the answer to an add.
- `primarySkill` is the first skill tag.
- `verificationStatus` is null when verification-service cannot be asked.
- `assignable` means on the team, verification `APPROVED` and not under review, and is false whenever verification could not be asked.

Every portal call answers 404 `TENANT_NOT_FOUND` when the caller administers no Tenant, and 403 `TENANT_SUSPENDED` for a suspended Tenant.

`TenantBooking = {id, reference, serviceName, status, isEmergency, scheduledAt, createdAt, queuedAt,
amount, currency, address, coordinates, providerId}`. `address` (the saved address's label) and `coordinates` are null when customer-service cannot answer. `queuedAt` is null for a booking matched by dispatch.

- The queue holds `AWAITING_ASSIGNMENT` bookings that the Tenant was a candidate for and nobody holds yet, or that a provider declined back to this Tenant.
- `GET /tenant/bookings` lists only bookings carrying this Tenant's id: those it assigned, those declined back to it, and automatic matches of its own providers. `status` is one optional value, and an unknown one gives 400.
- `{key}` is the booking UUID or reference.

The assignment answers:

- 404 `BOOKING_NOT_FOUND` when the booking is neither the Tenant's nor one it was a candidate for.
- 409 `BOOKING_NOT_ASSIGNABLE` unless the booking is `AWAITING_ASSIGNMENT` and unheld or this Tenant's. This is also what the loser of a concurrent assignment gets.
- 409 `PROVIDER_NOT_ASSIGNABLE` unless the provider is an assignable member. Availability is not checked.
- 503 `TENANT_DIRECTORY_UNAVAILABLE` when provider-service cannot be asked.

**Assigned provider** (booking-service, no role rule; the caller must be the booking's `providerId`, staff included otherwise get 404):

- `POST /bookings/{key}/assignment/acceptance` → `PROVIDER_ACCEPTED` and `ProviderAccepted`. Repeating it after success answers 200 and publishes nothing.
- `POST /bookings/{key}/assignment/rejection` → back to `AWAITING_ASSIGNMENT` with the provider cleared. The Tenant and the original queue time stay, so the booking returns to that Tenant only and its deadline is not reset. It is allowed only for a booking that came through the Tenant fallback, else 409 `INVALID_BOOKING_TRANSITION`, and it publishes nothing.

Both answer a `BookingResponse`, and 409 `BOOKING_CHANGED` when a concurrent change (typically the timeout sweeper) won.

`GET /bookings/{key}` carries `tenantName` only while the booking is `PROVIDER_ASSIGNED`, has a Tenant, and provider-service returns the Tenant's name.

**Entering and leaving the queue** (booking-service).

- When dispatch-engine reports that nobody accepted, booking-service asks provider-service which ACTIVE Tenants cover the booking's address and category. If any do, it snapshots them as candidates and moves the booking to `AWAITING_ASSIGNMENT`, publishing nothing. Tenants find the booking by polling the queue.
- If none cover it, or a lookup fails, the booking fails as before.
- `AssignmentTimeoutSweeper` runs every minute, 100 bookings a pass. It fails any queued booking still `AWAITING_ASSIGNMENT` or `PROVIDER_ASSIGNED` once `homefix.booking.tenant-assignment-timeout` (default `PT60M`, env `TENANT_ASSIGNMENT_TIMEOUT`) has passed since it was first queued.
- A failed booking goes to `SEARCHING_FAILED`, passing through `AWAITING_ASSIGNMENT` when a provider had been assigned, and publishes `BookingCancelled`. That event still names the provider, so they are told the job is off.
- A late acceptance is then refused with 409 `INVALID_BOOKING_TRANSITION`.

**Internal** (service credential `X-Internal-Api-Key`, not routed; a bad key gives 401 `INTERNAL_AUTH_FAILED`):

| Service | Method & path | Answer |
|---|---|---|
| auth | `GET /internal/users/by-mobile?mobileNumber=` | `{userId, roles[], status}`; 404 `USER_NOT_FOUND` |
| auth | `POST`/`DELETE /internal/users/{userId}/roles/{role}` | 200 `{userId, roles[], status}`, idempotent; only `TENANT_ADMIN` (else 400 `ROLE_NOT_MANAGEABLE`); DELETE also revokes every refresh session |
| auth | `GET /internal/users/{userId}/contact` | `{userId, mobileNumber, emailAddress}`, for member lists |
| provider | `GET /internal/tenants/covering?lat&lon&categoryId` | `{tenants:[{tenantId, name, distanceKm}]}`, ACTIVE Tenants whose radius covers the point, nearest first |
| provider | `GET /internal/tenants/by-admin/{userId}` | `{tenantId, name, status}`; 404 `TENANT_NOT_FOUND` |
| provider | `GET /internal/tenants/{tenantId}` | `{tenantId, name, status}`; 404 `TENANT_NOT_FOUND` |
| provider | `GET /internal/tenants/{tenantId}/providers/{providerId}` | `{member, assignable, verificationStatus}`; 404 `PROVIDER_NOT_FOUND` when not a member |
| provider | `GET /internal/tenants/of-provider/{providerId}` | `{tenantId, name}`; 404 `TENANT_NOT_FOUND` for an independent provider |

**Errors**, by service:

- provider-service, admin and team changes:
  - 400: the Tenant validation codes above, and `VALIDATION_ERROR`.
  - 404: `TENANT_NOT_FOUND`, `USER_NOT_FOUND` (no account for the number, or removing someone who is not an admin), `PROVIDER_NOT_FOUND` (no provider account or profile, or not on this team).
  - 409: `ADMIN_OF_OTHER_TENANT`, `PROVIDER_IN_OTHER_TENANT`, `TENANT_CONCURRENTLY_MODIFIED`.
  - 503: `AUTH_UNAVAILABLE`.
- Both services: `TENANT_NOT_FOUND` 404 and `TENANT_SUSPENDED` 403.
- booking-service:
  - 404: `BOOKING_NOT_FOUND`.
  - 409: `BOOKING_NOT_ASSIGNABLE`, `PROVIDER_NOT_ASSIGNABLE`, `INVALID_BOOKING_TRANSITION`, `BOOKING_CHANGED` (a concurrent change won on acceptance or rejection).
  - 503: `TENANT_DIRECTORY_UNAVAILABLE`.
- auth-service: `ROLE_NOT_MANAGEABLE` 400.

**Events**:

- `ProviderAssigned` gains nullable `tenantId` and `tenantName` (`tenantName` from the assigning admin's Tenant lookup, null whenever `tenantId` is). It is now published only when a Tenant assigns; automatic dispatch passes through `PROVIDER_ASSIGNED` without it.
- A Tenant assignment that the provider accepts publishes `ProviderAccepted` from booking-service with the dispatch payload `{bookingId, customerId, providerId, bookingCreatedAt, acceptedAt}`.
- Entering the queue and declining publish nothing.

---

### notification-service and outbox-processor

outbox-processor declares no controller; it is the relay, and exposes only health, metrics and Prometheus. notification-service is a Kafka consumer whose contracts are the events below, plus one admin controller for its message templates. It makes one outbound call, auth-service's internal contact lookup (see auth-service). It has no handler for `/notifications/**`, which the gateway still routes, and no `/internal/notifications/**` either. dispatch-engine and invoice-service call the latter without a credential, and every such call is refused.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/admin/notification-templates` | ADMIN, SUPER_ADMIN | Every built-in template |
| PUT | `/admin/notification-templates/{id}` | ADMIN, SUPER_ADMIN | Edit one template's text |

```json
{
  "id": "PROVIDER_ASSIGNED.PROVIDER.PUSH",
  "key": "PROVIDER_ASSIGNED.PROVIDER",
  "name": "Provider assigned (provider)",
  "channel": "PUSH",
  "subject": "New job assigned",
  "body": "You have been assigned {{bookingReference}}. Open the app to accept or decline it."
}
```

- `id` is `<key>.<channel>`. The set of templates is fixed in code; only `subject` and `body` can be changed, through `PUT` with `{subject, body}`.
- A null subject keeps the current one. Nulls are omitted, so an SMS template has no `subject`.
- An unknown id gives 404 `TEMPLATE_NOT_FOUND`.
- 400 `INVALID_TEMPLATE` (with `details`) covers: a blank body, a body over 1000 characters or a subject over 200, a placeholder the event does not supply, a subject on SMS, or a blank subject.
- A missing body gives 400 `VALIDATION_ERROR`.
- Edits are cached for 60 seconds per replica and evicted on commit.

---

## Event contracts

### The outbox envelope

Producers never talk to Kafka. `OutboxEventPublisher.publish(aggregateType, aggregateId, eventType, payload)` is annotated `@Transactional(propagation = MANDATORY)`, so calling it outside a transaction throws by design and the event row always commits with the business state change.

The `outbox_event` row carries `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `status`, `retry_count`, `created_at`, `published_at`, `next_attempt_at`, `last_error` and a `@Version` column, indexed on status and creation time. `next_attempt_at` is the relay's: null (what producers write) means due now.

The relay then sends:

| Element | Value |
|---------|-------|
| Topic | From the mapping below, else `domain-events` |
| Key | `aggregate_id` as a string, so all events for one aggregate keep order on one partition |
| Headers | `eventId` only, the outbox row id. There is no `eventType` or `aggregateType` header |
| Value | The `payload` column verbatim, with no wrapping envelope |

The producer refuses to start unless `acks=all` and idempotence are enabled. The same `eventId` is reused across retries, never regenerated.

**Claiming and retries.** Every second the relay claims up to 100 due `PENDING` rows (`next_attempt_at` null or past), oldest first, with `SELECT ... FOR UPDATE SKIP LOCKED` in a short transaction that sets each row's `next_attempt_at` to now plus a 2-minute lease and commits. Concurrent relay instances therefore never work the same row, and the publish happens outside any transaction. Each claimed row gets one attempt, bounded by a 30-second ACK timeout (10 s `max.block.ms`, 25 s `delivery.timeout.ms`). A non-retriable failure increments `retry_count`, writes `last_error` and sets `next_attempt_at` to the next backoff step (1 second doubling to a 60-second cap); nothing sleeps. A retriable broker failure (a Kafka `RetriableException`, a timeout or an interrupt in the cause chain) writes `last_error` and reschedules at the 60-second cap without spending an attempt, so an outage cannot exhaust a row's budget. The attempt that reaches 10 marks the row `FAILED`, keeps `last_error` and alerts ops with the event id, topic and attempt count. The first failure in a cycle also gives the rest of that batch back unattempted, so a broker outage costs one attempt per cycle rather than one per row. A row with too little lease left to publish safely is skipped, and it and the rest of the batch are released. The relay refuses to start unless the lease exceeds the publish timeout plus `max.block.ms`. Re-queue a `FAILED` row with `UPDATE outbox.outbox_event SET status = 'PENDING', retry_count = 0, next_attempt_at = NULL WHERE id = ...`.

**Delivery is at-least-once.** An event can reach Kafka twice: the relay dies or loses its database after the broker ACK, a timed-out publish is delivered by the producer afterwards, or a relay outlives its lease (its stale outcome write is then rejected by the `@Version` check, but its publish has happened). Consumers deduplicate on `eventId`. Nothing is lost: a row only leaves `PENDING` on an ACK or an exhausted budget.

**Consumer-side dedupe.** `IdempotentKafkaConsumer` extracts `eventId`, dead-letters immediately if it is absent, skips if the `(consumerGroup, eventId)` row already exists, otherwise handles the record with up to 3 attempts 5 seconds apart. In every service with a database the marker is inserted in the same transaction as the handler, so a concurrent duplicate hits the unique key and rolls back. Exactly-once still does not extend to a handler's non-database side effects, such as HTTP calls. Only a service without a transaction manager writes the marker separately. The 5 seconds are not slept on the listener thread: the shared module's auto-configuration gives Spring Boot's listener-container factory a `DefaultErrorHandler` that pauses the container between attempts (`ContainerPausingBackOffHandler`, `homefix.outbox.consumer.retry-delay`, default `PT5S`) and turns on the delivery-attempt header, so the consumer rethrows attempts 1 and 2 for redelivery and dead-letters on attempt 3 itself.

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
| `ComplaintCreated` | `ComplaintCreated` |
| `ComplaintStatusChanged` | `ComplaintStatusChanged` |
| anything unmapped | `domain-events` |

### Event catalogue

| Event | Topic | Producer | Consumers |
|-------|-------|----------|-----------|
| `BookingCreated` | `BookingCreated` | booking-service, on confirmation or emergency creation | dispatch-engine, notification-service |
| `ProviderArriving` | `ProviderArriving` | booking-service | notification-service |
| `ProviderArrived` | `ProviderArrived` | booking-service | notification-service |
| `JobStarted` | `JobStarted` | booking-service | notification-service, location-service |
| `JobCompleted` | `JobCompleted` | booking-service, on completion or quote rejection | notification-service |
| `ProviderAccepted` | `ProviderAccepted` | dispatch-engine (automatic match); booking-service (provider accepts a Tenant assignment) | chat-service, notification-service |
| `PaymentCompleted` | `PaymentCompleted` | payment-service | invoice-service, rating-review-service, chat-service, notification-service, booking-service |
| `ReviewSubmitted` | `ReviewSubmitted` | rating-review-service | notification-service |
| `ProviderAssigned` | `ProviderAssigned` | booking-service, only when a Tenant assigns a provider | notification-service |
| `ProviderRejected` | `ProviderRejected` | dispatch-engine, when an offer is declined or times out | notification-service |
| `BookingCancelled` | `BookingCancelled` | booking-service, on entering `CANCELLED` or `SEARCHING_FAILED` | chat-service, notification-service, dispatch-engine |
| `ComplaintCreated` | `ComplaintCreated` | complaint-service | notification-service |
| `ComplaintStatusChanged` | `ComplaintStatusChanged` | complaint-service | notification-service |

Consumer records are all annotated to ignore unknown properties, so extra producer fields are tolerated. Missing fields deserialise to null or zero and then fail the consumer's own checks, which is where the mismatches below bite.

#### BookingCreated

Producer:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
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

**Resolved by enrichment in the consumer.** The producer never emits `customerLat`, `customerLon` or `requiredSkillTags`, and is not expected to: it publishes booking facts. dispatch-engine resolves them itself before matching. The `addressId` is looked up through customer-service's `GET /internal/addresses/{addressId}`, and the `subcategoryId` through the `skillTags` on catalog-service's `GET /catalog/categories`. A value the event does carry is used as-is. The consumer fields are boxed, so an absent value is never read as 0.0.

| Outcome | Handling |
|---------|----------|
| Both resolved, at least one skill tag | Dispatched on the resolved values |
| A dependency could not be consulted (timeout, 5xx, open breaker, 401/403, or a 404 without `ADDRESS_NOT_FOUND`) | `EnrichmentUnavailableException`. Retried by the shared consumer, then dead-lettered. Not recorded as processed, so it can be replayed |
| No coordinates and no `addressId`; `404 ADDRESS_NOT_FOUND`; the address's `customerId` differs from the booking's; subcategory absent from the active catalog; subcategory with no skill tags | `UnresolvableBookingException`. Not dead-lettered: dispatch-engine calls booking-service's `searching-failed` callback, so the booking goes to the Tenant fallback or to `SEARCHING_FAILED` and the customer is told. The event is then recorded as processed. Only an event with no `bookingId`, or a callback failing other than with 404 or 409, is retried and dead-lettered |

The consumer also reads `reference` and `scheduledAt` (shown on the offer) and `occurredAt` (used as `bookingCreatedAt`). The Pact in `dispatch-engine/src/test/resources/pacts` still describes the old denormalised shape. It still passes, because pre-enriched events are accepted, but booking-service's consumer test should be regenerated from the real producer shape.

#### ProviderArriving and ProviderArrived

Identical shape:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

`customerId` is the recipient notification-service addresses (it ignores `providerId` for these events). Before it was added, both events were dead-lettered and no notification was delivered.

#### JobStarted

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "startedAt": "2026-09-11T09:30:00Z",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

location-service consumes it (topic `JobStarted`, overridable with `homefix.location.topics.job-started`) and reads only `bookingId`, to end the tracking session. notification-service addresses it to `customerId`.

#### JobCompleted

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "completedAt": "2026-09-11T10:45:00Z",
  "netDurationSeconds": 4200,
  "finalTotal": 1674.72,
  "occurredAt": "2026-09-11T10:45:00Z"
}
```

notification-service addresses it to `customerId`.

#### ProviderAssigned

Written by `BookingTransitionService` in the same transaction as a transition into `PROVIDER_ASSIGNED` after which the booking rests there, awaiting the provider's acceptance. **In practice that means a Tenant assignment** (`POST /tenant/bookings/{key}/assignment`), the only path that leaves a booking in `PROVIDER_ASSIGNED`.

dispatch-engine's acceptance callback does **not** publish it. That callback assigns and accepts in one transaction, applying the intermediate step through `transitionPassingThrough`, which validates and audits it but publishes nothing. The acceptance is announced by dispatch-engine's `ProviderAccepted` alone, so the customer is not sent "assigned" immediately followed by "accepted".

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "bookingCreatedAt": "2026-09-11T09:25:00Z",
  "occurredAt": "2026-09-11T09:30:00Z",
  "tenantId": "5e2f8a17-3c94-4d06-b1a8-7f0c2e9d4b63",
  "tenantName": "Sunrise Home Services"
}
```

`tenantId` is the booking's Tenant, and `tenantName` is that Tenant's name as the assigning admin's lookup returned it. Both are nullable, and `tenantName` is forced to null whenever `tenantId` is.

notification-service notifies the customer ("Professional assigned … we will let you know as soon as they confirm") and the provider. With `tenantName` present the provider gets the `PROVIDER_ASSIGNED.PROVIDER.TENANT` template ("New job from {{tenantName}}"), otherwise `PROVIDER_ASSIGNED.PROVIDER`. Contact details are resolved from auth-service.

#### BookingCancelled

Written by `BookingTransitionService` in the same transaction as every transition into `CANCELLED` and into `SEARCHING_FAILED`.

- `CANCELLED` comes from a customer, provider or staff cancellation through `POST /bookings/{reference}/cancellation`, or from `POST /admin/bookings/{id}/cancel`.
- `SEARCHING_FAILED` comes from dispatch-engine's `searching-failed` callback when no Tenant covers the booking, or from the Tenant assignment timeout sweeper.

A rejected transition writes nothing. An idempotent retry of a dispatch callback does not write a second row, and neither does a booking routed to the Tenant queue (`AWAITING_ASSIGNMENT`).

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "previousStatus": "PROVIDER_ON_THE_WAY",
  "status": "CANCELLED",
  "cancelledBy": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "cancelledByRole": "CUSTOMER",
  "reason": "customer no longer available",
  "cancellationFee": 75.00,
  "bookingCreatedAt": "2026-09-11T09:25:00Z",
  "occurredAt": "2026-09-11T09:30:00Z"
}
```

- `providerId` is null when the booking is cancelled before a provider is assigned.
- `status` is `CANCELLED` or `SEARCHING_FAILED`. On `SEARCHING_FAILED`, `cancelledBy` is null, `cancelledByRole` is `booking-service` and `cancellationFee` is null.
- `cancellationFee` is the fee the cancellation policy applied: `0.00` before `PROVIDER_ON_THE_WAY`, the configured fee from then on. No per-subcategory fee source exists yet, so in practice it is the 0.00 default.
- When the sweeper fails a booking a provider had been assigned, `previousStatus` is `AWAITING_ASSIGNMENT` (it passes through it) and `providerId` is still set, so the provider is told the job is off.
- `reason` is the free text recorded in the audit trail, and may be null.

chat-service needs only `bookingId` to deactivate the channel, and dispatch-engine reads only `bookingId` to stop offering the booking and withdraw any pending offer. notification-service notifies the customer, and also the provider when `providerId` is set ("you do not need to attend"). It tells a `SEARCHING_FAILED` booking that no professional was available.

#### ProviderAccepted

Two producers write the identical record. dispatch-engine writes it after booking-service has accepted an automatic match, and booking-service writes it in the same transaction as a provider accepting a Tenant assignment.

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "bookingCreatedAt": "2026-09-11T09:25:00Z",
  "acceptedAt": "2026-09-11T09:30:00Z"
}
```

chat-service activates the channel from `bookingId`, `customerId` and `providerId`, so channels now open for both paths. notification-service tells the customer. The two producers fill `bookingCreatedAt` differently. booking-service uses the booking's creation time, and dispatch-engine uses `BookingCreated.occurredAt`, which is the confirmation time. dispatch-engine writes its row in a separate transaction after the booking-service call, so a failed outbox write there leaves an accepted booking unannounced.

#### ProviderRejected

Producer (dispatch-engine, one per declined or unanswered offer):

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "reason": "TIMED_OUT",
  "rejectedAt": "2026-09-11T09:31:00Z"
}
```

- `reason` is `REJECTED` (the provider declined) or `TIMED_OUT` (no answer within the offer window). The job-offer adapter also reports a failure to record the offer as `TIMED_OUT`.
- Written to the outbox in its own transaction, because a rejection changes no other dispatch-engine state. The write is best-effort: if it fails, the failure is logged and the search moves on to the next candidate.
- Nothing is published for an accepted offer, an offer withdrawn because the booking was cancelled, or a provider busy with another booking's offer (retried within one offer window). A provider declining a Tenant assignment publishes nothing either.
- notification-service reads `bookingId`, `customerId` and `providerId`. Under its recipient policy the customer is the recipient.

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

This is the one event with no schema drift: the invoice, rating and booking consumer records are field-for-field identical to the producer. Four copies of the same record exist in four modules, which is a standing duplication risk.

booking-service consumes it (group `booking-service.payment-completed`), reading `bookingId` and `paymentId`, to settle the booking at `PAYMENT_COMPLETED`:

- from `PAYMENT_PENDING` directly;
- from `JOB_COMPLETED` or `CUSTOMER_CONFIRMED` through the intermediate states (audited, not announced), since the money has moved whether or not the pending call was recorded.

An already completed booking is a no-op. A booking in any other state, or an unknown id, is logged and acknowledged, never retried. chat-service closes the channel. The aggregate id is the transaction id rather than the booking id, so these records key into a different partition space from the booking events.

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

notification-service notifies both parties: the reviewer is thanked and the reviewee is told they received a review.

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

Categories are `POOR_QUALITY`, `LATE_ARRIVAL`, `OVERCHARGING`, `UNPROFESSIONAL_BEHAVIOR`, `INCOMPLETE_WORK`, `DAMAGE`, `PAYMENT_ISSUE`. Statuses are `OPEN`, `IN_PROGRESS`, `ESCALATED`, `DISPUTED`, `REFUND_FAILED`, `RESOLVED`, `CLOSED`, with `previousStatus` null on the first transition. Both events are relayed to same-named topics (`ComplaintCreated`, `ComplaintStatusChanged`), and notification-service consumes both to notify the customer (Requirements 16.2, 16.3).

#### The notification-service consumer contract

One consumer group binds all eleven lifecycle topics and the two complaint topics (`ComplaintCreated`, `ComplaintStatusChanged`). Every payload deserialises into the same read model. Unknown fields are ignored and only these are read:

```json
{
  "bookingId": "b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55",
  "reference": "HFX-20260912-AB12CD",
  "customerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "providerId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "reviewerId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "revieweeId": "dd73094d-7dbb-4a7f-b96b-0fab756cb240",
  "complaintId": "e81c4f27-3a69-4b05-9d82-7f1e6c3a5b94",
  "newStatus": "IN_PROGRESS",
  "status": "CANCELLED",
  "tenantName": "Sunrise Home Services"
}
```

`reference` is also accepted under its older name, `bookingReference`. `tenantName` fills the `{{tenantName}}` placeholder of the Tenant-assigned templates (falling back to "HomeFix"); `tenantId` is ignored. `newStatus` is the complaint's new status. `status` is the booking status on `BookingCancelled`, where `SEARCHING_FAILED` is rendered as "no professional available" rather than as a cancellation.

**Events carry user ids, never contact details.** notification-service decides who each event concerns. It then fetches each recipient's addresses from auth-service's `GET /internal/users/{userId}/contact`, sending the shared `X-Internal-Api-Key`. Phone numbers stay out of Kafka and out of every producer. Any `mobileNumber`, `emailAddress` or `deviceToken` on an event is ignored.

Recipients per event:

| Event | Notified | Required field (else dead-lettered) |
|-------|----------|--------------------------------------|
| `BookingCreated`, `ProviderAccepted`, `ProviderRejected`, `ProviderArriving`, `ProviderArrived`, `JobStarted`, `JobCompleted` | customer | `customerId` |
| `ProviderAssigned`, `PaymentCompleted`, `BookingCancelled` | customer, plus the provider when `providerId` is present | `customerId` |
| `ReviewSubmitted` | reviewer ("review submitted") and reviewee ("new review"), each when present | `reviewerId` or `revieweeId` |
| `ComplaintCreated` | customer: the acknowledgement of Requirement 16.2 | `customerId` |
| `ComplaintStatusChanged` | customer: in-app and push, per Requirement 16.3 | `customerId` |

Failure handling, all through the shared consumer's retry (3 attempts) and `<topic>.DLT`:

- **An event that names no required recipient** is a producer defect. It is dead-lettered, with the missing field in `dlqReason`, so it can be replayed once the producer is fixed.
- **The contact lookup could not be done** is treated as transient. This covers a timeout (2 s connect/read, `NOTIFICATION_CONTACT_LOOKUP_TIMEOUT`), a connection failure, a 5xx, a 401 from a mismatched key, and a 404 that is not `USER_NOT_FOUND`. The event is retried and then dead-lettered. All contacts are resolved before anything is sent, so a retry starts clean.
- **A user auth-service does not know** (`404 USER_NOT_FOUND`), or a channel with no address, is a logged skip rather than an error. That channel is recorded as `SKIPPED_NO_CONTACT` with no retry. In-app delivery needs no address, so it still happens.

Producer status against this contract:

- Ready as published: `BookingCreated`, `ProviderAccepted`, `PaymentCompleted`, `ReviewSubmitted`, `ProviderAssigned`, `BookingCancelled`, `ProviderArriving`, `ProviderArrived`, `JobStarted`, `JobCompleted`, `ComplaintCreated`, `ComplaintStatusChanged`.
- `ProviderArriving`, `ProviderArrived`, `JobStarted` and `JobCompleted` now carry `customerId` as well as `providerId`, so they are no longer dead-lettered.
- `ProviderRejected` is produced by dispatch-engine (see above) with `customerId`, so it resolves a recipient. It is delivered in-app only: dispatch emits one per candidate that declines or lets an offer lapse, and a push for each would buzz the customer repeatedly while one booking is matched.

The delivery log is keyed on `(kafkaEventId, userId, channel)`. A single event can reach several recipients on the same channel, for example a cancellation sent to both customer and provider. Delivery channels are `PUSH`, `SMS`, `EMAIL`, `IN_APP`. Delivery statuses are `DELIVERED`, `PERMANENTLY_FAILED`, `SKIPPED_PREFERENCE`, `SKIPPED_NO_CONTACT`. Because auth-service stores only a phone number, SMS and in-app are the only channels that can deliver today. Nothing on the platform stores a push token. All four channel adapters are logging stubs.

---

## Contract defects summary

The extraction surfaced these, all verified in code. Items marked **fixed** are kept for the record with what fixed them.

1. **RBAC was unconfigured in 17 of 19 services.** Partly fixed: all eighteen servlet services now register role rules. Still open:
   - booking-service's `POST /bookings/**` commands and the assignment answers have no role rule and rely on in-code ownership, which judges staff by the token's first authority only.
   - location-service checks roles but not ownership, so any provider can post pings for any booking and any listed role can read any position.
2. **Chat channels were never activated** — **fixed**. `ProviderAccepted` now carries `customerId` from both producers, dispatch-engine and booking-service.
3. **Four notification topics were dead-lettered** — **fixed**. `ProviderArriving`, `ProviderArrived`, `JobStarted` and `JobCompleted` now carry `customerId`. Contact details come from auth-service by user id rather than from events.
4. **location-service subscribed to `booking.job-started`** — **fixed**. It now consumes `JobStarted`.
5. **Dispatch matching ran on null coordinates and null skill tags.** Fixed in two steps:
   - dispatch-engine resolves both from customer-service and catalog-service before matching.
   - provider-service now has `/internal/providers/eligible`.

   An unresolvable booking is now failed through booking-service rather than dead-lettered. Still open: the offer, "no provider" and dispatcher notifications go to notification-service `/internal/notifications/*` endpoints that do not exist, and are sent without a credential. Providers therefore only see offers by polling `GET /dispatch/offers`.
6. **`ProviderRejected`, `ProviderAssigned` and `BookingCancelled` had consumers but no producer.** Fixed:
   - dispatch-engine publishes `ProviderRejected`.
   - booking-service publishes `BookingCancelled`.
   - booking-service publishes `ProviderAssigned` for Tenant assignments, now the only path that rests in `PROVIDER_ASSIGNED`.
7. **Complaint events were unmapped** — **fixed**. Both are in the topic map, and notification-service consumes them.
8. **Two services lacked the shared error envelope.** invoice-service is **fixed**: it now has a handler. dispatch-engine still has only controller-local handlers, without `correlationId`. Several other handlers do not map unreadable bodies, missing parameters or type mismatches, which get Spring's default body: booking, payment, complaint, rating-review, promotion and reporting.
9. **Port and route inconsistencies.** The dispatch-engine shadowing and admin-service's shadowed pricing endpoint are **fixed**: dispatch-engine has its own routes, and admin-service no longer serves module endpoints. Still open:
   - `/notifications/**` is routed to a service with no handler there.
   - chat's `/ws/chat` is not routed.
   - The gateway demands a token on `/auth/logout`, `GET /catalog/**` and `/payments/callbacks/**`, which their services permit anonymously.
   - booking-service's `application.yml` default port is 8085, the same as catalog-service's; compose overrides it.
10. **Duplicate coupon error codes between promotion-service and pricing-engine** — **fixed**. pricing-engine now quotes coupons through promotion-service and passes its codes through.
11. **Payment retries can open a second charge.** `POST /payments/{id}/retries` can move a `PENDING` payment, with a gateway charge possibly in flight, to `FAILED`. That unlocks the next attempt on `POST /payments`. A late `SUCCEEDED` callback for the first attempt then gets 409 `CALLBACK_CONFLICT`, leaving captured money unrecorded.
12. **A queued booking does not protect its address.** booking-service's `/internal/bookings/active` does not count `AWAITING_ASSIGNMENT`, so a customer can delete the address of a booking waiting in a Tenant queue.
