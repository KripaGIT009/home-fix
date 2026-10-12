# Design Document — Email Sign-Up, Email/Password Sign-In, Agency Sign-Up and Staff Invitations

## Overview

Implements [requirements.md](requirements.md). The Auth_Service gains email credentials, email
verification, password reset, staff invitations and an email port; the Provider_Service gains agency
applications on top of the existing Tenant model; the three apps gain sign-up, email sign-in and the
related screens. No new service.

### Key decisions

| # | Decision | Why |
|---|---|---|
| D1 | Email lives on `auth.user_account` (nullable, unique on `lower(email)`), with `email_verified_at` and `display_name` | One account per person across roles (platform Requirement 1.14); existing OTP accounts are untouched. |
| D2 | Unverified sign-ups are accounts in status `PENDING_VERIFICATION` | Reuses the account and status machinery (`AccountStatus`), so sign-in, refresh and introspection already refuse them; a sweep removes ones older than 24 h. |
| D3 | Codes and reset tokens live in Redis next to the OTP store | Same TTL, attempt-count and rate-limit pattern as OTP (`RedisOtpStore`, `RedisLoginAttemptStore`). |
| D4 | Invitations are a table in the `auth` schema | They must survive restarts for 7 days and be listed and revoked. Only the SHA-256 of the token is stored. |
| D5 | Agency sign-up = a basic account + a Tenant in `PENDING_APPROVAL` | No role is granted before approval; approval reuses the existing "add Tenant admin" path (grant `TENANT_ADMIN`). |
| D6 | The Auth_Service owns all email delivery | One email port and one place that holds templates; the Provider_Service asks for agency decision emails through an internal endpoint. |

---

## Components and Interfaces

### auth-service

**Data** — migration `V4__email_credentials.sql`:

```sql
ALTER TABLE auth.user_account ADD COLUMN email varchar(254), ADD COLUMN email_verified_at timestamptz,
  ADD COLUMN display_name varchar(80);
CREATE UNIQUE INDEX uq_user_account_email ON auth.user_account (lower(email)) WHERE email IS NOT NULL;
-- AccountStatus gains PENDING_VERIFICATION: widen the status check constraint.
CREATE TABLE auth.staff_invitation (
  id uuid PRIMARY KEY, email varchar(254) NOT NULL, role varchar(32) NOT NULL,
  token_hash char(64) NOT NULL UNIQUE, invited_by uuid NOT NULL, created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL, accepted_at timestamptz, accepted_user_id uuid, revoked_at timestamptz,
  CHECK (role IN ('ADMIN','FINANCE_ADMIN','DISPATCHER','SUPPORT_AGENT')));
CREATE UNIQUE INDEX uq_staff_invitation_open ON auth.staff_invitation (lower(email))
  WHERE accepted_at IS NULL AND revoked_at IS NULL;
```

**Public endpoints** (no token; routed by the apps' `/api/auth/` proxy like today's `/auth/**`):

| Method & path | Body | Answer |
|---|---|---|
| `POST /auth/register/email` | `{displayName, email, mobileNumber, password, role}` | 202 `{status:"CODE_SENT"}` always (enumeration-safe); 409 `MOBILE_IN_USE`; 400 `INVALID_ROLE` / `WEAK_PASSWORD` / `VALIDATION_ERROR` |
| `POST /auth/register/email/verify` | `{email, code}` | 200 `TokenResponse` (signed in); 400 `INVALID_CODE`; 410 `CODE_EXPIRED` |
| `POST /auth/register/email/resend` | `{email}` | 202 always; 429 `TOO_MANY_REQUESTS` |
| `POST /auth/login/password` | `{identifier, password}` (the old `username` field still accepted) | 200 `TokenResponse`; 401 `INVALID_CREDENTIALS`; 403 `EMAIL_NOT_VERIFIED` / `ACCOUNT_DISABLED` |
| `POST /auth/password/forgot` | `{email}` | 202 always |
| `POST /auth/password/reset` | `{email, code, newPassword}` | 204; 400 `INVALID_CODE`; revokes all refresh families |
| `GET /auth/invitations/{token}` | — | 200 `{email, role, invitedByName, expiresAt, existingAccount}`; 410 `INVITATION_EXPIRED` |
| `POST /auth/invitations/{token}/acceptance` | new account `{displayName, mobileNumber, password}` or existing `{password}` | 200 `TokenResponse` |

**Signed-in endpoints**: `GET /auth/me` → `{userId, displayName, email, emailVerified, mobileNumber, roles}`;
`POST /auth/me/email` `{email, currentPassword?}` → 202 code sent; `POST /auth/me/email/verify` `{code}` →
204; `PUT /auth/me/password` `{currentPassword?, newPassword}` → 204 (`currentPassword` required when a
password exists).

**Admin endpoints** (`/admin/invitations`, gateway route to auth-service):
`GET /admin/invitations` (open ones), `POST /admin/invitations {email, role}` → 201, `DELETE
/admin/invitations/{id}` → 204. RBAC: ADMIN and SUPER_ADMIN; the service enforces that only SUPER_ADMIN may
invite `ADMIN` (403 `SUPER_ADMIN_REQUIRED`).

**Internal**: `POST /internal/emails/agency-decision {userId, tenantName, approved, reason?}` → 202
(provider-service is the caller).

**Email port**: `EmailSenderPort.send(to, subject, body)` with `FileEmailSenderAdapter` (appends to
`homefix.email.file-path`, default `/var/log/homefix/dev-mail.log`; Compose mounts it next to the dev SMS
log as `docker/dev-mail/dev-mail.log`) and `LoggingEmailSenderAdapter` (default; logs recipient and
subject only). Selected by `homefix.email.provider` (`file` | `logging`), mirroring `homefix.sms.provider`.

**Codes**: 6 digits from `SecureRandom`, stored hashed in Redis per purpose (`signup`, `reset`, `email-change`)
with a 10-minute TTL and an attempt counter (max 5); rate limits in Redis per email and per IP (resend ≥ 60 s
apart, ≤ 5/h; sign-up and reset ≤ 10/h per IP).

**Sign-up flow**: validate → if the email belongs to an active account, email "you already have an account"
and answer 202; if the mobile belongs to another account (active or pending < 24 h), 409 `MOBILE_IN_USE`;
otherwise create or replace a `PENDING_VERIFICATION` account (pending ones older than 24 h are replaced) with
the role, hash the password, send the code, 202. Verify → status `ACTIVE`, `email_verified_at`, tokens.

**Sweep**: `@Scheduled` hourly removal of `PENDING_VERIFICATION` accounts older than 24 h.

### provider-service

- `TenantStatus` gains `PENDING_APPROVAL` and `REJECTED` (migration widening the check; `tenant` gains
  `rejection_reason varchar(500)`, `applicant_user_id uuid`).
- `POST /tenant-applications` (any authenticated user; RBAC rule `POST /tenant-applications` for all roles)
  with the Tenant body → 201 `{tenantId, name, status}`; 409 `APPLICATION_EXISTS` if the user already has a
  pending or active agency or administers one.
- `GET /tenant-applications/me` → `{tenantId, name, status, rejectionReason}` or 404.
- `POST /admin/tenants/{id}/approval` → `ACTIVE` + add the applicant as admin (existing grant path) + decision
  email; `POST /admin/tenants/{id}/rejection {reason}` → `REJECTED` + decision email; 409 when not pending.
- `GET /admin/tenants?status=` filter; coverage keeps returning `ACTIVE` only.
- Gateway: route `/tenant-applications/**` to provider-service.

### Apps

- **customer-app / provider-app**: the login screen gains an **Email** tab (email + password, "Forgot
  password?", "Create account"); a sign-up screen (name, email, mobile, password, confirm) → a code screen
  (6-box input, reused from OTP, with resend) → signed in; a reset flow (email → code + new password); an
  `EMAIL_NOT_VERIFIED` sign-in offers to resend and opens the code screen; Profile gains "Email & password".
  Role sent: `CUSTOMER` / `SERVICE_PROVIDER` respectively.
- **admin-portal**: password sign-in field labelled "Email or username"; **Register your agency** (account
  step reusing the email sign-up endpoints with role `CUSTOMER`, then the agency form), an **application
  status** page for signed-in applicants without a staff role; **Accept invitation** page at
  `/invite/:token`; Users module gains an **Invitations** tab (invite, list, revoke); Tenants module gains a
  **Pending applications** filter with approve / reject.

---

## Correctness Properties

### Property EA1: No enumeration
*For any* email, the responses to sign-up, resend and password-reset requests SHALL be identical in status and
body whether or not the email is registered. **Validates: 1.3, 3.1, 8.2**

### Property EA2: Codes are single-use and bounded
*For any* code, at most one successful use SHALL be possible, it SHALL be rejected after 10 minutes or after 5
wrong attempts. **Validates: 1.6, 3.3**

### Property EA3: No path to a privileged role
*For any* sequence of public or self-service requests, no Account SHALL gain `SUPER_ADMIN`, and Staff_Roles
SHALL be gained only through an invitation created by an authorised admin. **Validates: 6.1, 8.4**

### Property EA4: Pending agencies are inert
*For any* Tenant not `ACTIVE`, it SHALL cover no booking and its applicant SHALL hold no `TENANT_ADMIN` role.
**Validates: 5.3**

### Property EA5: Reset ends sessions
*For any* successful password reset, every refresh-token family of the Account issued before the reset SHALL be
revoked. **Validates: 3.2**

---

## Testing Strategy

Unit and web-layer tests per endpoint in each module's style; Redis-backed stores tested with the in-memory
fakes used by the OTP tests; enumeration-safety tests compare full responses; invitation token hashing and
expiry; agency approval through the auth stub. End to end: extend `docker/verify-outbox-flow.sh` (or a new
`docker/verify-signup.sh`) reading codes and invitation links from `docker/dev-mail/dev-mail.log`.

## Decisions taken during implementation (2026-10-04)

| # | Decision | Why |
|---|---|---|
| D7 | `auth.user_account.mobile_verified_at` records when OTP proved the number (existing rows backfilled). An OTP sign-in over a number that was only *given* at email sign-up never signs in to that account: an unverified sign-up holding it is removed, an active email account gives the number up, and the OTP holder gets their own account | Email sign-up records the mobile unverified (Requirement 1, product decision). Without this, anyone could sign up with someone else's number and later see what that person does after they sign in by OTP |
| D8 | An email held only by a `PENDING_VERIFICATION` sign-up does not block the person who proves it (profile email change, invitation acceptance); the unverified sign-up is removed | Squatting an address without its code must not lock its owner out |
| D9 | A password reset lifts the sign-in lockout on the address as well as revoking every refresh family | The emailed code proves control of the account; a locked-out owner who resets expects to sign in at once |
| D10 | `POST /auth/me/email/verify` answers 200 with the `GET /auth/me` body rather than 204 | The profile screen shows the new state without a second request |
| D11 | The logging email adapter logs the subject only, never the recipient | Requirement 7.2 and platform Requirement 26.4 (no PII in logs) |
| D12 | Setting a password needs a verified email or a username first (`EMAIL_REQUIRED`) | A password with nothing to sign in with is unusable |
| D13 | An application's status changes only by approval or rejection: `PUT /admin/tenants/{id}` and adding admins to an undecided application answer 409 `APPLICATION_NOT_DECIDED`. Approval grants and records the admin first, then activates; if activation fails the admin is removed again | Property EA4: no path grants `TENANT_ADMIN` for, or activates, an undecided application |
| D14 | `docker/verify-signup.sh` approves an agency at 0°N 0°E with a 1 km radius | The check must not create a real-looking agency that takes local bookings |

## Open Questions

1. A production email provider (SES, SendGrid…) — only the port and local adapters are built.
2. Should agency applications also require document verification of the company?
