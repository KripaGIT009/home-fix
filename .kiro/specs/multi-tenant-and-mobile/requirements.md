# Requirements Document — Multi-Tenant Agencies and Mobile Apps

## Introduction

HomeFix today matches each booking to an individual Provider automatically. When no Provider accepts
within the search radius cycles, the booking ends in `SEARCHING_FAILED` and the customer is told no one is
available (platform spec, Requirement 8.9).

This feature adds two capabilities:

1. **Tenants (service agencies) inside the one HomeFix marketplace.** A Tenant is a service company that
   owns a team of Providers and covers a service area for a set of service categories. Customers, the
   catalog and pricing stay shared: customers keep booking HomeFix, not a Tenant. When automatic matching
   finds no Provider, the booking is routed to the Tenants covering its address and service instead of
   failing, and a Tenant administrator assigns one of the Tenant's own Providers, who confirms the job.
2. **Web, Android and iOS apps for Customers and Providers from one codebase.** The existing customer and
   provider web apps are packaged as Android and iOS apps with Capacitor, keeping the web apps.

Decisions taken with the product owner on 2026-10-03:

| Decision | Choice |
|---|---|
| Tenant model | Agency inside one marketplace (shared customers, catalog, pricing; tenant-scoped data) |
| Which Tenant receives a request | Tenants whose service area and categories cover the booking |
| When a Tenant administrator steps in | Only when automatic matching finds no Provider |
| Mobile | Web apps plus Android and iOS apps from the same codebase |

Out of scope for this feature: white-label branding, per-Tenant catalog or pricing, Tenant commission or
payouts (Provider earnings keep flowing to the Provider's wallet), push notifications to Tenant
administrators (the Tenant portal polls), and publishing to the app stores.

---

## Glossary

- **Tenant**: A service company (agency) registered on HomeFix that owns a team of Providers and covers a
  Service_Area for a set of Service_Categories.
- **Tenant_Admin**: A user holding the `TENANT_ADMIN` role who manages exactly one Tenant: its team and its
  Assignment_Queue.
- **Platform_Admin**: A user holding `ADMIN` or `SUPER_ADMIN` (platform spec glossary).
- **Independent_Provider**: A Provider who belongs to no Tenant. Independent Providers keep working exactly
  as today.
- **Tenant_Provider**: A Provider who belongs to a Tenant. A Provider belongs to at most one Tenant.
- **Service_Area**: A circle defined by a base location (latitude, longitude) and a radius in kilometres.
- **Coverage**: A Tenant covers a booking when the booking's service address lies within the Tenant's
  Service_Area and the booking's Service_Category is one of the Tenant's categories.
- **Candidate_Tenant**: A Tenant that covered a booking at the moment the booking entered
  `AWAITING_ASSIGNMENT`.
- **Assignment_Queue**: The bookings in `AWAITING_ASSIGNMENT` for which a Tenant is a Candidate_Tenant.
- **Assignable_Provider**: A Tenant_Provider of the same Tenant whose verification status is `APPROVED` and
  who is not under review.
- **Tenant_Portal**: The web interface a Tenant_Admin uses, delivered inside the Admin Portal application.
- **Native_App**: The Android or iOS build of the customer app or provider app produced with Capacitor.

---

## Requirements

### Requirement 1: Tenant Registry

**User Story:** As a Platform_Admin, I want to register service agencies with the area and services they
cover, so that bookings HomeFix cannot match automatically can be routed to them.

#### Acceptance Criteria

1. THE Provider_Service SHALL store each Tenant with an id, name, status (`ACTIVE` or `SUSPENDED`), optional
   contact phone and email, a Service_Area (base latitude, base longitude, radius in km) and a non-empty set
   of Service_Category ids.
2. WHEN a Platform_Admin creates a Tenant, THE Provider_Service SHALL validate that the name is 2–120
   characters, latitude is within [-90, 90], longitude within [-180, 180], the radius within [1, 100] km and
   every category id refers to an active Service_Category, and SHALL reject an invalid request with 400 and
   an errorCode identifying the failed rule.
   *As built:* the rule codes are `INVALID_TENANT_NAME`, `INVALID_CONTACT_PHONE`, `INVALID_CONTACT_EMAIL`,
   `INVALID_LATITUDE`, `INVALID_LONGITUDE`, `INVALID_SERVICE_RADIUS`, `CATEGORIES_REQUIRED`,
   `INACTIVE_CATEGORY` (and `INVALID_TENANT_STATUS` on update). The "active Service_Category" check is
   **not enforced yet**: provider-service's catalog client is a stub that treats every non-null id as
   active, so `INACTIVE_CATEGORY` cannot fire. Reason: no HTTP catalog adapter exists in provider-service;
   the Admin Portal only offers categories from the active catalog, which covers the normal path.
3. WHEN a Platform_Admin updates a Tenant's details, Service_Area, categories or status, THE
   Provider_Service SHALL apply the change atomically and record who made it and when; every Tenant,
   administrator and membership change SHALL also be logged with the acting user's id.
   *As built:* two concurrent edits of one Tenant are settled by its version column; the later one is
   refused with 409 `TENANT_CONCURRENTLY_MODIFIED` instead of silently overwriting the earlier one.
4. WHILE a Tenant is `SUSPENDED`, THE Provider_Service SHALL exclude it from Coverage and its Tenant_Admins
   SHALL be refused every Tenant_Portal action with 403 `TENANT_SUSPENDED`; its Providers SHALL remain
   eligible for automatic matching.
5. THE Provider_Service SHALL list Tenants for Platform_Admins with their provider count and admin count.

### Requirement 2: Tenant Administrators

**User Story:** As a Platform_Admin, I want to make a person the administrator of a Tenant, so that the
agency can manage its own team and requests.

#### Acceptance Criteria

1. THE Auth_Service SHALL support a `TENANT_ADMIN` role that cannot be self-assigned at registration
   (platform spec Requirement 1.13).
2. WHEN a Platform_Admin adds a Tenant_Admin by mobile number, THE Provider_Service SHALL look the account up
   in the Auth_Service, grant it `TENANT_ADMIN`, and record the account as an administrator of that Tenant;
   IF no account has that mobile number, THEN it SHALL answer 404 `USER_NOT_FOUND`.
3. A user SHALL administer at most one Tenant; adding an administrator of another Tenant SHALL be refused
   with 409 `ADMIN_OF_OTHER_TENANT`.
4. WHEN a Platform_Admin removes a Tenant_Admin, THE Provider_Service SHALL remove the membership and the
   Auth_Service SHALL revoke the `TENANT_ADMIN` role; the change SHALL take effect no later than the user's
   next token refresh.
5. THE Auth_Service SHALL include a newly granted or revoked role in tokens issued after the change (login or
   refresh), without requiring the user to register again.
6. *As built (addition):* IF the Auth_Service cannot be reached while adding or removing a Tenant_Admin or
   adding a Provider, THEN THE Provider_Service SHALL answer 503 `AUTH_UNAVAILABLE` and make no change
   (a removal keeps the membership). The Auth_Service's internal role endpoints refuse any role other than
   `TENANT_ADMIN` with 400 `ROLE_NOT_MANAGEABLE`. Reason: an outage must not look like "no such user", and a
   leaked service credential must not be able to grant platform roles.
7. *As built (addition):* `TENANT_ADMIN` is not a platform staff role for the Admin Portal's user-status rule,
   so an `ADMIN` (not only a `SUPER_ADMIN`) may suspend or reactivate a Tenant_Admin account; an account that
   also holds `ADMIN` or `SUPER_ADMIN` still needs a `SUPER_ADMIN`. Reason: ADMINs appoint and remove Tenant
   administrators (Requirement 12.3) and must be able to stop one they appointed.

### Requirement 3: Tenant Team Membership

**User Story:** As a Tenant_Admin, I want to manage which Providers belong to my agency, so that I can assign
my team to jobs.

#### Acceptance Criteria

1. THE Provider_Service SHALL record at most one Tenant per Provider; Providers without a Tenant SHALL remain
   Independent_Providers.
2. WHEN a Tenant_Admin or Platform_Admin adds a Provider by mobile number, THE Provider_Service SHALL attach
   the Provider to the Tenant IF the account holds `SERVICE_PROVIDER` and has a provider profile; otherwise
   it SHALL answer 404 `PROVIDER_NOT_FOUND`. A Provider already in another Tenant SHALL be refused with 409
   `PROVIDER_IN_OTHER_TENANT`.
3. WHEN a Tenant_Admin or Platform_Admin removes a Provider, THE Provider_Service SHALL detach it; bookings
   already assigned to that Provider SHALL be unaffected.
4. THE Provider_Service SHALL list a Tenant's Providers with display name, primary skill, verification
   status, aggregate rating and whether the Provider is available now according to their availability slots.
5. Tenant membership SHALL NOT change automatic matching: Tenant_Providers and Independent_Providers SHALL
   be scored and offered jobs by the same rules (platform spec Requirement 8).

### Requirement 4: Fallback Routing to Tenants

**User Story:** As a Customer, I want my request handed to a local agency when no professional accepts it
automatically, so that I still get help instead of a "no one available" message.

#### Acceptance Criteria

1. WHEN the Dispatch_Engine reports that no Provider accepted a booking after all radius cycles, THE
   Booking_Service SHALL determine the booking's Coverage by the service address's coordinates and the
   booking's Service_Category.
2. IF at least one `ACTIVE` Tenant covers the booking, THEN THE Booking_Service SHALL record every covering
   Tenant as a Candidate_Tenant, transition the booking from `SEARCHING_PROVIDER` to `AWAITING_ASSIGNMENT`,
   and record the time it entered the queue; it SHALL NOT publish `BookingCancelled` at this point.
3. IF no Tenant covers the booking, THEN THE Booking_Service SHALL transition the booking to
   `SEARCHING_FAILED` exactly as before (platform spec Requirement 8.9).
4. IF the Coverage lookup or the address lookup fails, THEN THE Booking_Service SHALL transition the booking
   to `SEARCHING_FAILED` and log the dependency failure, so that a dependency outage never leaves a booking
   in `SEARCHING_PROVIDER`.
5. WHILE a booking is `AWAITING_ASSIGNMENT`, THE Customer app SHALL present it as still finding a
   professional, stating that a local partner is assigning one.

### Requirement 5: Tenant Assignment

**User Story:** As a Tenant_Admin, I want to see requests in my area that could not be matched and assign one
of my Providers, so that the customer is served.

#### Acceptance Criteria

1. THE Booking_Service SHALL show a Tenant_Admin their Tenant's Assignment_Queue, oldest first, with the
   reference, service name, emergency flag, scheduled time, time queued, amount, and the service address and
   coordinates.
2. WHEN a Tenant_Admin assigns a booking to a Provider, THE Booking_Service SHALL verify that the booking is
   `AWAITING_ASSIGNMENT`, that the caller's Tenant is a Candidate_Tenant (or, after a decline, the booking's
   Tenant), and that the Provider is an Assignable_Provider of that Tenant.
3. WHEN the checks in criterion 2 pass, THE Booking_Service SHALL record the Tenant and Provider on the
   booking, transition it to `PROVIDER_ASSIGNED`, and publish a `ProviderAssigned` event; the
   Notification_Service SHALL notify the Customer and the Provider.
4. IF two Tenant_Admins assign the same booking concurrently, THEN exactly one assignment SHALL succeed and
   the other SHALL receive 409 `BOOKING_NOT_ASSIGNABLE`.
5. IF the Provider is not an Assignable_Provider of the caller's Tenant, THEN THE Booking_Service SHALL refuse
   with 409 `PROVIDER_NOT_ASSIGNABLE` and leave the booking unchanged.
6. THE Booking_Service SHALL answer 404 for a booking that is neither in the caller's Assignment_Queue nor
   the caller's Tenant's booking, indistinguishable from a missing booking.

### Requirement 6: Provider Confirmation of an Assignment

**User Story:** As a Tenant_Provider, I want to confirm or decline a job my agency assigned me, so that I am
never committed to a job I cannot do.

#### Acceptance Criteria

1. WHEN the assigned Provider accepts a `PROVIDER_ASSIGNED` booking, THE Booking_Service SHALL transition it
   to `PROVIDER_ACCEPTED` and publish a `ProviderAccepted` event with the same payload the Dispatch_Engine
   publishes (bookingId, customerId, providerId, bookingCreatedAt, acceptedAt), so chat, notifications and
   tracking behave exactly as for an automatically matched job.
2. WHEN the assigned Provider declines, THE Booking_Service SHALL clear the Provider, return the booking to
   `AWAITING_ASSIGNMENT` for the same Tenant, and keep the original queue time.
3. Only the assigned Provider SHALL accept or decline; any other caller SHALL receive 404.
   *As built:* "any other caller" includes platform staff (`ADMIN`, `SUPER_ADMIN`, `SUPPORT_AGENT`,
   `DISPATCHER`, `FINANCE_ADMIN`), who are admitted to every other provider job command. Reason: the answer is
   the Provider's consent (design D6), which nobody can give on their behalf. Repeating an acceptance that
   already succeeded returns the booking unchanged and publishes nothing, so an app retry is safe.
4. THE Provider app SHALL show a `PROVIDER_ASSIGNED` job with the assigning Tenant's name and Accept and
   Decline actions.

### Requirement 7: Assignment Timeout

**User Story:** As a Customer, I want to be told promptly if no agency can take my request, so that I can make
other arrangements.

#### Acceptance Criteria

1. THE Booking_Service SHALL transition a booking that went through the Tenant fallback and is still waiting
   for a confirmed Provider longer than a configurable timeout (default 60 minutes,
   `TENANT_ASSIGNMENT_TIMEOUT`) to `SEARCHING_FAILED`, with the existing customer notifications. "Still
   waiting" covers both `AWAITING_ASSIGNMENT` (no Tenant assigned anyone) and `PROVIDER_ASSIGNED` (the
   assigned Provider never answered); the latter moves `PROVIDER_ASSIGNED → AWAITING_ASSIGNMENT →
   SEARCHING_FAILED` in one transaction, both steps audited, using only the transitions of Requirement 9.1.
   *As built (changed):* the original text covered `AWAITING_ASSIGNMENT` only. Reason: the customer was
   promised an answer within the timeout, and an unanswered assignment must not hold the booking forever.
   The Provider stays on the failed booking, so the cancellation notice tells them the job is off, and a
   late acceptance is refused as an illegal transition. Automatically dispatched bookings, which pass
   through `PROVIDER_ASSIGNED` without a queue time, are never touched.
2. THE timeout SHALL be measured from the time the booking first entered the queue; a Provider's decline
   SHALL NOT restart it.
3. THE Booking_Service SHALL check for expired assignments at least once a minute and SHALL be safe to run on
   more than one instance (no double transition).
   *As built:* the booking's optimistic lock settles a sweep racing another instance, a Tenant assignment or
   a Provider's acceptance; a user request that loses such a race answers 409 `BOOKING_CHANGED` ("reload and
   try again") instead of an opaque 500.

### Requirement 8: Tenant Oversight

**User Story:** As a Tenant_Admin, I want to follow every job my team is doing, so that I can manage quality
and workload.

#### Acceptance Criteria

1. THE Booking_Service SHALL record the Tenant on a booking whenever the Provider who accepts it belongs to a
   Tenant, whether through a Tenant assignment or through automatic matching.
2. IF the Provider's Tenant cannot be determined at acceptance, THEN the acceptance SHALL still succeed and
   the booking SHALL carry no Tenant.
3. THE Booking_Service SHALL list a Tenant_Admin's Tenant's bookings, newest first, optionally filtered by
   status, bounded to 200 rows.
4. THE Tenant_Portal SHALL show each Provider's current availability and verification status alongside the
   Assignment_Queue so the Tenant_Admin can choose whom to assign.

### Requirement 9: Booking State Machine Changes

**User Story:** As an engineer, I want the new states and transitions defined precisely, so that every
service enforces the same lifecycle.

#### Acceptance Criteria

1. THE Booking_Service SHALL add the state `AWAITING_ASSIGNMENT` and the following transitions to the
   permitted map (platform spec Requirement 9.1): `SEARCHING_PROVIDER → AWAITING_ASSIGNMENT`;
   `AWAITING_ASSIGNMENT → PROVIDER_ASSIGNED | SEARCHING_FAILED | CANCELLED`;
   `PROVIDER_ASSIGNED → AWAITING_ASSIGNMENT` (in addition to the existing `PROVIDER_ACCEPTED | CANCELLED`).
2. Every new transition SHALL be audited with actor and reason like existing transitions (platform spec
   Requirement 9.15).
3. A booking cancelled while `AWAITING_ASSIGNMENT` or `PROVIDER_ASSIGNED` SHALL carry no cancellation fee
   (platform spec Requirement 9.16).
4. THE database SHALL accept the new state value; existing bookings SHALL be unaffected by the migration.

### Requirement 10: Authorization and Data Isolation

**User Story:** As a Tenant, I want my team and requests visible only to my administrators, so that competing
agencies cannot see my business.

#### Acceptance Criteria

1. Every Tenant_Portal endpoint SHALL require the `TENANT_ADMIN` role and SHALL resolve the caller's Tenant
   from the caller's identity, never from a request parameter.
2. A Tenant_Admin SHALL see only their own Tenant's details, Providers, Assignment_Queue and bookings;
   requests for another Tenant's resources SHALL answer 404.
3. Platform_Admins SHALL manage all Tenants through the Admin Portal; Tenant_Admins SHALL NOT reach platform
   administration endpoints (they answer 403).
4. Customer-facing and Provider-facing behaviour SHALL be unchanged for bookings that never enter
   `AWAITING_ASSIGNMENT`.
5. Service-to-service lookups added by this feature SHALL use the existing internal credential
   (`X-Internal-Api-Key`) and SHALL NOT be routed by the API Gateway.
6. *As built (addition):* a caller holding `TENANT_ADMIN` who administers no Tenant (for example just
   removed, with an access token issued before) SHALL receive 404 `TENANT_NOT_FOUND` from every Tenant_Portal
   endpoint of both services. IF the Booking_Service cannot ask the Provider_Service for the caller's Tenant,
   THEN its Tenant endpoints SHALL answer 503 `TENANT_DIRECTORY_UNAVAILABLE`. Reason: "not an admin" and
   "directory down" must stay distinguishable; turning an outage into a 404 would mislead the admin.

### Requirement 11: Tenant Portal

**User Story:** As a Tenant_Admin, I want a web console for my agency, so that I can work the queue and manage
my team from any browser.

#### Acceptance Criteria

1. THE Admin Portal SHALL let a `TENANT_ADMIN` sign in and SHALL show only the Tenant modules: Requests (the
   Assignment_Queue), Team, and Jobs (the Tenant's bookings); Requests SHALL be the landing page.
2. THE Requests module SHALL refresh the queue at least every 15 seconds and show the number of waiting
   requests in the navigation.
3. THE assign action SHALL let the Tenant_Admin choose among the Tenant's Assignable_Providers, showing
   availability now, and SHALL report a lost race (409) by refreshing the queue with an explanation.
4. THE Team module SHALL add Providers by mobile number and remove them, showing the errors of Requirement 3.

### Requirement 12: Platform Administration of Tenants

**User Story:** As a Platform_Admin, I want to manage Tenants from the Admin Portal, so that I can onboard
agencies without database access.

#### Acceptance Criteria

1. THE Admin Portal SHALL provide a Tenants module for `ADMIN` and `SUPER_ADMIN` listing Tenants with status,
   coverage summary, provider count and admin count.
2. THE Tenants module SHALL create and edit a Tenant: name, contact, base location, radius, categories (chosen
   from the active catalog) and status.
3. THE Tenants module SHALL add and remove Tenant_Admins and Providers by mobile number, showing the errors of
   Requirements 2 and 3.

### Requirement 13: Customer and Provider App Changes

**User Story:** As a Customer or Provider, I want the apps to explain the new states, so that I always know
what happens next.

#### Acceptance Criteria

1. THE Customer app SHALL present `AWAITING_ASSIGNMENT` as "finding a professional" with partner-assignment
   copy, and `PROVIDER_ASSIGNED` as "professional assigned, waiting for their confirmation", on the tracking,
   booking detail and history screens.
2. THE Provider app SHALL list `PROVIDER_ASSIGNED` jobs on the dashboard and SHALL offer Accept and Decline on
   the job screens (Requirement 6).
3. Both apps SHALL keep polling a booking in these states until it moves on.

### Requirement 14: Android and iOS Apps

**User Story:** As a Customer or Provider, I want HomeFix on my phone as an installed app as well as on the
web, so that I can use it the way I prefer.

#### Acceptance Criteria

1. THE customer app and the provider app SHALL each build as a web app, an Android app and an iOS app from
   the same source code.
2. THE Native_Apps SHALL read the API and auth base URLs from build configuration and SHALL refuse to start
   without absolute URLs (no development proxy exists inside a native shell).
3. THE provider Native_App SHALL share the Provider's location while on the way (Requirement 10.1 of the
   platform spec) using the device's native location service, requesting permission with an explanation;
   IF permission is denied, THEN the app SHALL say so, as the web app does.
4. THE Native_Apps SHALL support photo capture and upload for job photos and booking media, requesting camera
   and photo-library permission with an explanation.
5. THE iOS projects SHALL declare usage descriptions for location, camera and photo library; the Android
   projects SHALL declare the corresponding permissions.
6. THE repository SHALL document how to build, run and debug each Native_App, including that iOS builds
   require macOS with Xcode and an Apple developer account, and how to point a device at a local backend.
7. THE web apps SHALL continue to work unchanged in browsers.

*As built (open):* criteria 1 and 3–5 are implemented in the Android and iOS projects, but no native build
has been run. The development machine has no Android SDK, only JDK 25 (Gradle 8.11 needs 17 or 21) and no
macOS, so web build, `cap sync`, typecheck and lint are verified while Gradle and Xcode builds are not. See
`frontend/MOBILE.md`, "Known gaps".

### Requirement 15: Quality, Migration and Documentation

**User Story:** As an engineer, I want the feature delivered with migrations, tests and documentation, so that
it can be deployed and maintained safely.

#### Acceptance Criteria

1. Every schema change SHALL be a new Flyway migration; existing migrations SHALL NOT be edited.
2. Existing bookings, Providers and accounts SHALL keep working after migration with no data backfill
   required (all new columns nullable or defaulted).
3. Each service change SHALL have unit and web-layer tests for its new rules, including authorization
   refusals and concurrency (Requirement 5.4).
4. THE end-to-end check `docker/verify-outbox-flow.sh` SHALL cover a booking that falls back to a Tenant, is
   assigned, declined, reassigned, accepted and completed.
5. THE local seed data SHALL include a demo Tenant covering the seeded service area with a Tenant_Admin
   account and the seeded Providers as members.
6. `docs/API_CONTRACTS.md`, `docs/ARCHITECTURE.md` and `docs/LOCAL_ACCESS.md` SHALL describe the new
   endpoints, states, flows, accounts and mobile build steps.
