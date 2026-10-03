# Implementation Plan: Multi-Tenant Agencies and Mobile Apps

## Overview

This plan implements [requirements.md](requirements.md) following [design.md](design.md). Tasks are sized for
1–3 developer days and grouped into five phases. Dependencies are explicit; tasks in the same phase without a
dependency between them can run in parallel.

- **Phase 1** — Identity and Tenant registry (Tasks 1–3)
- **Phase 2** — Booking fallback, assignment and confirmation (Tasks 4–8)
- **Phase 3** — Web interfaces: Tenant Portal, Tenants module, app states (Tasks 9–12)
- **Phase 4** — Android and iOS apps (Tasks 13–15)
- **Phase 5** — Seeds, end-to-end verification, documentation (Tasks 16–18)

Conventions for every task: schema changes are new Flyway migrations (never edit existing ones); new
user-facing paths get RBAC rules; new internal paths use the existing internal credential; tests follow the
module's existing style; Java modules are tested with the capped-heap command in `docs/LOCAL_ACCESS.md`.

## Tasks

### Phase 1 — Identity and Tenant Registry

- [x] 1. Auth Service — `TENANT_ADMIN` role and internal role management
  - Add `Role.TENANT_ADMIN` (not self-assignable) and migration `V3__tenant_admin_role.sql` widening `user_account_role_role_check` (Requirement 2.1)
  - Add internal `GET /internal/users/by-mobile?mobileNumber=` returning `{userId, roles, status}`, 404 `USER_NOT_FOUND` (Requirement 2.2)
  - Add internal `POST` / `DELETE /internal/users/{userId}/roles/TENANT_ADMIN`, idempotent, refusing any other role with 400; revoke the user's refresh-token families on revoke (Requirement 2.4)
  - Verify that login and refresh read roles from the account so a grant or revoke appears in the next token; fix if refresh reuses stored roles (Requirement 2.5)
  - Write tests: role not self-assignable, by-mobile found/not found, grant/revoke idempotence, other roles refused, missing internal key refused, refreshed token carries the new role
  - **Dependencies:** none
  - **Acceptance:** a user granted `TENANT_ADMIN` sees it in the token after refresh; revoking removes it and ends existing sessions; all auth-service tests pass

- [x] 2. Provider Service — Tenant registry, administrators and membership
  - Migration `V3__tenants.sql`: `tenant`, `tenant_category`, `tenant_admin`, `provider_profile.tenant_id`, indexes (Design: provider-service Data)
  - Entities, repositories, `TenantService` with validation rules of Requirement 1.2 and category check through the existing catalog client
  - Platform admin endpoints `/admin/tenants/**` (list, create, update incl. status, members, add/remove admin by mobile, add/remove provider by mobile) with errors `TENANT_NOT_FOUND`, `USER_NOT_FOUND`, `ADMIN_OF_OTHER_TENANT`, `PROVIDER_NOT_FOUND`, `PROVIDER_IN_OTHER_TENANT` (Requirements 1, 2.2–2.4, 3.2–3.3, 12)
  - Auth client for `by-mobile` and role grant/revoke (internal key, timeouts, resilience as in existing adapters); grant before recording membership, compensate on failure
  - `updated_by` and INFO logging of every Tenant, admin and membership change (Requirement 1.3)
  - RBAC rules for `GET/POST/PUT/DELETE /admin/tenants/**` (ADMIN, SUPER_ADMIN)
  - Write tests: validation boundaries (radius 1/100, coordinates), uniqueness of admin and provider membership (Property MT8), suspended exclusion, controller tests through the security chain, auth client against a stub server
  - **Dependencies:** Task 1
  - **Acceptance:** a Platform_Admin can create a Tenant, add an admin and providers by mobile; constraints hold; provider-service tests pass

- [x] 3. Provider Service — Tenant admin endpoints and internal lookups
  - `/tenant/me`, `/tenant/providers` (list with `availableNow`, `assignable`, verification status via the existing batch call), add/remove provider by mobile; Tenant resolved from the subject; 403 `TENANT_SUSPENDED` (Requirements 3, 8.4, 10.1–10.2)
  - Internal `GET /internal/tenants/covering?lat&lon&categoryId` (haversine via `GeoMath`, `ACTIVE` only, nearest first), `/internal/tenants/by-admin/{userId}`, `/internal/tenants/{tenantId}/providers/{providerId}`, `/internal/tenants/of-provider/{providerId}` (Design: provider-service Internal)
  - RBAC rules for `/tenant/**` (TENANT_ADMIN)
  - Write tests: coverage boundary property test (Property MT2), isolation (another Tenant's provider → 404, Property MT5), assignable = member ∧ APPROVED ∧ not under review, internal key required
  - **Dependencies:** Task 2
  - **Acceptance:** coverage and assignability answer correctly at the boundaries; a Tenant_Admin cannot see another Tenant; tests pass

### Phase 2 — Booking Fallback, Assignment and Confirmation

- [x] 4. Booking Service — state, schema and tenant directory client
  - Add `AWAITING_ASSIGNMENT` and the transitions of Requirement 9.1 to `BookingStatus` / `BookingStateMachine`; fee-free cancellation in both new pre-acceptance states (Requirement 9.3)
  - Migration `V3__tenant_assignment.sql`: widen status checks on `booking` and `booking_audit`, add `tenant_id`, `queued_for_assignment_at`, `booking_tenant_candidate`, indexes (Requirement 9.4)
  - `TenantDirectoryPort` + HTTP adapter for the four provider-service internal endpoints (internal key, timeouts, shared resilience; 404 as an answer, not an outage)
  - Write tests: state machine table including every new transition and rejections (platform Property 8), migration applies on a database with existing bookings, adapter against a stub server
  - **Dependencies:** Task 3
  - **Acceptance:** state machine tests pass; migration applies cleanly on the local database

- [x] 5. Booking Service — fallback routing and timeout sweeper
  - In the `searching-failed` path: resolve address coordinates (`CustomerAddressPort`), call `covering`, insert candidates and transition to `AWAITING_ASSIGNMENT`, else `SEARCHING_FAILED`; lookup failures → `SEARCHING_FAILED` with WARN (Requirements 4.1–4.4, Property MT1)
  - Answer the `searching-failed` call with the resulting status
  - `@Scheduled` sweeper (60 s) moving bookings older than `homefix.booking.tenant-assignment-timeout` (default `PT60M`) to `SEARCHING_FAILED`; safe under concurrency (Requirement 7, Property MT6)
  - On `provider-accepted` from dispatch: set `tenant_id` from `of-provider`, best effort (Requirements 8.1–8.2)
  - Write tests: covered vs not covered vs lookup failure, no `BookingCancelled` on fallback, sweeper boundary and decline-does-not-extend, two sweepers racing, acceptance survives a failed tenant lookup
  - **Dependencies:** Task 4
  - **Acceptance:** a booking with a covering Tenant reaches `AWAITING_ASSIGNMENT` and times out to `SEARCHING_FAILED`; one without stays on today's path

- [x] 6. Booking Service — Tenant endpoints and assignment
  - `GET /tenant/bookings/queue`, `GET /tenant/bookings?status=`, `POST /tenant/bookings/{key}/assignment {providerId}` with `TenantBooking` responses (address via `CustomerAddressPort`) and the caller's Tenant cached 60 s (Requirements 5.1, 8.3)
  - Assignment checks and transition to `PROVIDER_ASSIGNED` with the `ProviderAssigned` outbox event; errors `BOOKING_NOT_ASSIGNABLE`, `PROVIDER_NOT_ASSIGNABLE`, 404 for out-of-scope bookings, 403 `TENANT_SUSPENDED` (Requirements 5.2–5.6)
  - RBAC rules for `/tenant/bookings/**` (TENANT_ADMIN)
  - Write tests: queue scoping per Tenant (Property MT5), concurrent assignment with two transactions (Property MT3), non-member / unapproved provider refused (Property MT4), event written in the same transaction
  - **Dependencies:** Task 5
  - **Acceptance:** two Tenants see only their queue; exactly one of two concurrent assignments succeeds

- [x] 7. Booking Service — Provider confirmation of an assignment
  - `POST /bookings/{key}/assignment/acceptance` → `PROVIDER_ACCEPTED` with the outbox `ProviderAccepted` event in the Dispatch Engine's payload shape (Requirement 6.1, Property MT7)
  - `POST /bookings/{key}/assignment/rejection` → `AWAITING_ASSIGNMENT`, provider cleared, Tenant and queue time kept (Requirements 6.2, 7.2)
  - Assigned Provider only through `BookingAccess` (Requirement 6.3); `tenantName` on the booking detail while `PROVIDER_ASSIGNED`
  - Write tests: accept/decline happy paths, wrong provider 404, wrong state 409, event payload equals dispatch's
  - **Dependencies:** Task 6
  - **Acceptance:** chat and notifications fire for a Tenant-assigned acceptance exactly as for an automatic one

- [x] 8. Dispatch Engine, Notification Service and API Gateway
  - Dispatch: skip its no-provider customer and dispatcher notices when booking-service answers `AWAITING_ASSIGNMENT` (Design: dispatch-engine)
  - Notification: provider `ProviderAssigned` copy names the assigning Tenant when present; verify customer copy
  - Gateway routes `/admin/tenants/**` and `/tenant/**` → provider-service, `/tenant/bookings/**` → booking-service, before the `/admin/**` catch-all; rebuild the gateway jar (review 17.3)
  - Compose and Helm: any new env (`TENANT_ASSIGNMENT_TIMEOUT`, provider-service URL for booking-service)
  - Write tests: dispatch notice suppression; gateway route order test
  - **Dependencies:** Tasks 5, 6
  - **Acceptance:** `/tenant/**` and `/admin/tenants/**` reach the right services through the gateway; no "no provider" notice is sent for a booking routed to a Tenant

### Phase 3 — Web Interfaces

- [x] 9. Admin Portal — Tenant Portal for `TENANT_ADMIN`
  - Allow `TENANT_ADMIN` sign-in; Tenant-only navigation and landing on Requests (Requirement 11.1)
  - Requests: queue polled every 15 s with a count badge; assign dialog listing the team with availability and assignability; 409 handling by refresh and message (Requirements 11.2–11.3)
  - Team: list, add by mobile, remove, errors of Requirement 3 (Requirement 11.4); Jobs: Tenant bookings with status filter (Requirement 8.3)
  - Typecheck, lint, build; screenshots at desktop and phone widths
  - **Dependencies:** Tasks 3, 6
  - **Acceptance:** a Tenant_Admin can assign a queued booking end to end in the browser and sees nothing outside their Tenant

- [x] 10. Admin Portal — Tenants module for Platform_Admins
  - List with status, coverage summary, counts; create/edit (categories from the catalog, base location, radius, status); members (admins and providers by mobile) (Requirement 12)
  - Typecheck, lint, build; screenshots
  - **Dependencies:** Task 2
  - **Acceptance:** a Platform_Admin can onboard a Tenant, its admin and providers without database access

- [x] 11. Customer App — new booking states
  - `AWAITING_ASSIGNMENT` as searching with partner copy, `PROVIDER_ASSIGNED` as assigned-and-confirming, on tracking, detail and history; keep polling (Requirements 4.5, 13.1, 13.3)
  - Typecheck, lint, build; screenshots
  - **Dependencies:** Task 4
  - **Acceptance:** a customer always sees an accurate, non-error message through fallback and assignment

- [x] 12. Provider App — accept or decline an assignment
  - Dashboard lists `PROVIDER_ASSIGNED` jobs; job screens show "Assigned by <tenant>" with Accept and Decline; polling while assigned; Accept continues to the active-job flow (Requirements 6.4, 13.2)
  - Typecheck, lint, build; screenshots
  - **Dependencies:** Task 7
  - **Acceptance:** an assigned provider can accept and complete a job, or decline and see it leave their list

### Phase 4 — Android and iOS Apps

- [x] 13. Native location and permissions in the provider app
  - Add `@capacitor/geolocation`; `useShareLocation` uses it when `env.isNative`, browser geolocation otherwise; permission denied handled as on the web (Requirement 14.3)
  - Android: camera permission; iOS (after Task 14): location, camera and photo library usage descriptions (Requirements 14.4–14.5)
  - **Dependencies:** Task 12
  - **Acceptance:** the provider Android app shares location from a device or emulator after granting permission

- [x] 14. iOS projects for the customer and provider apps
  - `npx cap add ios` in both apps; app ids `com.homefix.customer` / `com.homefix.provider`; `Info.plist` usage descriptions; `mobile:sync:ios` and `mobile:open:ios` scripts; ATS note for a local HTTP backend (Requirements 14.1, 14.5)
  - Customer app: camera and photo-library permissions on both platforms for booking media (Requirement 14.4)
  - **Dependencies:** none (can start in Phase 1)
  - **Acceptance:** `ios/` projects are generated and committed; `npx cap sync ios` succeeds; building in Xcode on macOS is documented (not possible on Windows)

- [x] 15. Mobile documentation and build verification
  - Extend `frontend/MOBILE.md`: iOS prerequisites (macOS, Xcode, Apple developer account), simulator and device runs, pointing a device at a local backend through the machine's LAN address, the new plugins and permissions (Requirement 14.6)
  - Build the Android debug APK of both apps where the Android SDK is available and record the result; confirm the web builds are unchanged (Requirement 14.7)
  - **Dependencies:** Tasks 13, 14
  - **Acceptance:** a developer can follow MOBILE.md to run each app on Android and iOS
  - **Status (2026-10-03):** documentation done; the Android debug build could not be run on the development machine (no Android SDK; its JDK 25 is too new for Gradle 8.11) and iOS builds need macOS — both remain to be verified on a machine with the toolchains

### Phase 5 — Seeds, End-to-End Verification and Documentation

- [x] 16. Local seed data for Tenants
  - `docker/seed-tenants.sql` (or extend the seed scripts): demo Tenant covering the seeded Ara service area for all seeded categories, a `tenantadmin` account (`TENANT_ADMIN`), the seeded providers as members; idempotent (Requirement 15.5)
  - **Dependencies:** Tasks 1, 2
  - **Acceptance:** after seeding, `tenantadmin` signs in to the Tenant Portal and sees the team

- [x] 17. End-to-end verification
  - Extend `docker/verify-outbox-flow.sh`: make the seeded providers unavailable to automatic matching (e.g. offers declined), assert fallback to `AWAITING_ASSIGNMENT`, Tenant queue visibility, assignment, decline, reassignment, acceptance, then the existing job and payment checks; isolation check with a second Tenant (Requirement 15.4)
  - Run `smoke-flows.sh` and module test suites; rebuild and restart the stack
  - **Dependencies:** Tasks 8, 16
  - **Acceptance:** both scripts pass on a cold restart

- [x] 18. Documentation
  - `docs/API_CONTRACTS.md` (new endpoints and errors), `docs/ARCHITECTURE.md` (state machine, fallback sequence), `docs/LOCAL_ACCESS.md` (`tenantadmin`, Tenant Portal, mobile run), and a review entry in `CODEBASE_REVIEW.md` (Requirement 15.6)
  - **Dependencies:** Task 17
  - **Acceptance:** documentation matches the deployed behaviour

## Traceability

| Requirement | Tasks |
|---|---|
| 1 Tenant Registry | 2, 10 |
| 2 Tenant Administrators | 1, 2, 10 |
| 3 Team Membership | 2, 3, 9, 10 |
| 4 Fallback Routing | 5, 11 |
| 5 Tenant Assignment | 6, 9 |
| 6 Provider Confirmation | 7, 12 |
| 7 Assignment Timeout | 5 |
| 8 Tenant Oversight | 3, 5, 6, 9 |
| 9 State Machine | 4 |
| 10 Authorization and Isolation | 3, 6, 8 |
| 11 Tenant Portal | 9 |
| 12 Platform Administration | 10 |
| 13 App Changes | 11, 12 |
| 14 Android and iOS | 13, 14, 15 |
| 15 Quality and Docs | all; 16, 17, 18 |
