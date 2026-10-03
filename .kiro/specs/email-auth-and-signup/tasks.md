# Implementation Plan: Email Sign-Up, Email/Password Sign-In, Agency Sign-Up and Staff Invitations

## Overview

Implements [requirements.md](requirements.md) following [design.md](design.md), in four phases. Tasks in a
phase without a dependency between them can run in parallel. Conventions as in the multi-tenant spec: new
Flyway migrations only, RBAC rules for new paths, tests in each module's style, Java builds one at a time
with the capped-heap command.

## Tasks

### Phase 1 — Auth Service

- [ ] 1. Email credentials, sign-up and verification
  - Migration V4 (email, email_verified_at, display_name, PENDING_VERIFICATION status, staff_invitation table) (Requirement 9.1)
  - `POST /auth/register/email`, `/verify`, `/resend`; Redis code store with TTL, attempts and rate limits; pending-account sweep (Requirements 1, 8)
  - `EmailSenderPort` with file and logging adapters; templates for code, "already registered", reset, invitation, agency decision (Requirement 7)
  - Tests: validation, enumeration safety (Property EA1), code bounds (EA2), rate limits, mobile conflict, replacement of stale pending accounts
  - **Dependencies:** none
  - **Acceptance:** a sign-up's code appears in the dev mail log and verifying it signs the user in

- [ ] 2. Email sign-in, password reset and self-service credentials
  - `POST /auth/login/password` accepts `identifier` (email or username), `EMAIL_NOT_VERIFIED` (Requirement 2)
  - `/auth/password/forgot`, `/auth/password/reset` revoking all refresh families (Requirement 3, EA5)
  - `GET /auth/me`, `POST /auth/me/email` + verify, `PUT /auth/me/password` (Requirement 4)
  - Tests: lockout per identifier, unverified sign-in, reset single-use and session revocation, current-password rule
  - **Dependencies:** Task 1
  - **Acceptance:** a user can reset a forgotten password and their old sessions stop working

- [ ] 3. Staff invitations and the agency decision email
  - `/admin/invitations` (list, create, revoke) with role rules (SUPER_ADMIN for ADMIN), hashed tokens, 7-day expiry, re-invite replaces (Requirement 6)
  - `GET /auth/invitations/{token}`, `POST /auth/invitations/{token}/acceptance` for new and existing accounts
  - Internal `POST /internal/emails/agency-decision` (Requirement 7.3)
  - Tests: privilege property (EA3), expiry/revocation 410, existing-account path, audit logging
  - **Dependencies:** Task 1
  - **Acceptance:** an invited dispatcher accepts the link and signs in to the Admin Portal with that role

### Phase 2 — Provider Service and gateway

- [ ] 4. Agency applications
  - Tenant statuses `PENDING_APPROVAL` / `REJECTED`, `rejection_reason`, `applicant_user_id` (new migration)
  - `POST /tenant-applications`, `GET /tenant-applications/me`, `/admin/tenants/{id}/approval|rejection`, status filter (Requirement 5)
  - Approval reuses the add-admin grant path; decision emails via the auth internal endpoint
  - Tests: one application per user, pending tenants inert (EA4), approve/reject state rules, compensation on grant failure
  - **Dependencies:** Task 3
  - **Acceptance:** an approved application becomes an ACTIVE Tenant whose applicant can open the Tenant Portal

- [ ] 5. Gateway, Compose and Helm
  - Gateway routes `/admin/invitations/**` → auth-service, `/tenant-applications/**` → provider-service (route-order test)
  - Compose: `EMAIL_PROVIDER=file`, mount `docker/dev-mail` like `docker/dev-sms`; Helm keeps the logging adapter
  - **Dependencies:** Tasks 1, 4

### Phase 3 — Apps

- [ ] 6. Customer and provider apps: email sign-up, sign-in, reset, profile credentials
  - Email tab, sign-up, code screen with resend, forgot/reset, `EMAIL_NOT_VERIFIED` handling, Profile "Email & password" (Requirements 1, 2.5, 3, 4.3)
  - Typecheck, lint, build; screenshots
  - **Dependencies:** Tasks 1, 2

- [ ] 7. Admin Portal: email sign-in, agency sign-up, invitations, approvals
  - "Email or username" sign-in; Register your agency; application status page; Accept invitation page; Users → Invitations tab; Tenants → pending applications with approve/reject (Requirements 2.5, 5, 6)
  - Typecheck, lint, build; screenshots
  - **Dependencies:** Tasks 3, 4

### Phase 4 — Verification and documentation

- [ ] 8. End-to-end check and seeds
  - `docker/verify-signup.sh`: sign-up → code from dev mail log → verify → email sign-in → reset; agency application → approve → Tenant Portal reachable; invitation → accept → staff sign-in (Requirement 9.3)
  - **Dependencies:** Tasks 5, 6, 7

- [ ] 9. Documentation
  - `docs/LOCAL_ACCESS.md` (where codes and invitation links appear), `docs/API_CONTRACTS.md`, app READMEs, review entry (Requirement 9.4)
  - **Dependencies:** Task 8

## Traceability

| Requirement | Tasks |
|---|---|
| 1 Email sign-up | 1, 6 |
| 2 Email sign-in | 2, 6, 7 |
| 3 Password reset | 2, 6 |
| 4 Add email/password | 2, 6 |
| 5 Agency sign-up | 4, 7 |
| 6 Staff invitations | 3, 7 |
| 7 Email delivery | 1, 3, 5 |
| 8 Security | 1, 2, 3, 5 |
| 9 Quality and docs | all; 8, 9 |
