# Requirements Document — Email Sign-Up, Email/Password Sign-In, Agency Sign-Up and Staff Invitations

## Introduction

Today a HomeFix account is created by mobile OTP (customer and provider apps) or seeded for local use
(staff). Password sign-in exists only by username, and the customer and provider apps offer no password
sign-in at all. Staff accounts and agency (Tenant) administrators can only be created by a platform admin.

This feature lets people create accounts themselves where that is safe, and sign in with email and
password in all three apps:

- **Customer and provider apps:** sign up with name, email, mobile number and password; verify the email
  with a 6-digit code; sign in with email and password; reset a forgotten password. Mobile OTP and social
  sign-in keep working.
- **Admin Portal:** two self-service paths, neither of which can create a platform administrator:
  - **Agency sign-up** — a service company registers itself and its first administrator; the agency is
    usable only after a platform admin approves it.
  - **Staff invitations** — a super admin (or admin, for non-admin roles) invites a person by email with a
    staff role; the invitee sets their own password from the invitation.
  Staff and agency admins sign in with email and password.

Decisions taken with the product owner on 2026-10-03:

| Decision | Choice |
|---|---|
| Admin Portal sign-up | Agency sign-up (approved by a platform admin) and staff invitations; no open staff sign-up |
| Email verification | Required, by a 6-digit emailed code, before the first sign-in |
| Mobile number on email sign-up | Required (SMS is the working notification channel; not verified at sign-up) |
| Process | Specification first, then implementation |

Out of scope: a production email provider (an adapter port is defined; locally codes go to a dev mail
log), two-factor authentication, social sign-up in the Admin Portal, and verifying the mobile number of an
email sign-up by OTP.

---

## Glossary

- **Account**: A user account in the Auth_Service (`auth.user_account`), with one or more Roles.
- **Email_Credential**: An Account's email address (unique, case-insensitive) and password hash.
- **Verification_Code**: A 6-digit, single-use code emailed to prove control of an address; it expires
  after 10 minutes and allows at most 5 wrong attempts.
- **Dev_Mail_Log**: A local file the Auth_Service appends emails to when no email provider is configured
  (the counterpart of today's dev SMS log).
- **Agency_Application**: A Tenant in status `PENDING_APPROVAL` together with the Account that applied to
  administer it.
- **Staff_Invitation**: A single-use, expiring invitation for an email address to join with one staff role.
- **Staff_Role**: `ADMIN`, `FINANCE_ADMIN`, `DISPATCHER` or `SUPPORT_AGENT`. `SUPER_ADMIN` is never granted
  through this feature.

---

## Requirements

### Requirement 1: Email Sign-Up for Customers and Providers

**User Story:** As a customer or provider, I want to create an account with my email and a password, so that
I can sign in without waiting for an SMS code.

#### Acceptance Criteria

1. THE Auth_Service SHALL accept a sign-up request with display name (2–80 characters), email, mobile number
   (E.164), password and role (`CUSTOMER` or `SERVICE_PROVIDER`); any other role SHALL be refused with 400
   `INVALID_ROLE` (platform spec Requirement 1.13).
2. THE password SHALL be 8–72 characters and contain at least one letter and one digit; it SHALL be stored
   only as a bcrypt hash.
3. WHEN the request is valid, THE Auth_Service SHALL create the Account in status `PENDING_VERIFICATION`,
   email a Verification_Code, and answer 202 without revealing whether the email or mobile number was
   already registered.
4. IF the email already belongs to an active Account, THEN THE Auth_Service SHALL send that address a
   "you already have an account" email instead of a code and SHALL NOT create or modify any Account.
5. IF the mobile number already belongs to another Account, THEN THE Auth_Service SHALL refuse with 409
   `MOBILE_IN_USE` and explain that the person can sign in with that mobile and add an email in their
   profile (Requirement 4).
6. WHEN the correct Verification_Code is submitted, THE Auth_Service SHALL activate the Account, record the
   email as verified, and issue tokens (signed in).
7. A sign-up that is never verified SHALL be removable: an unverified Account older than 24 hours SHALL
   not block a new sign-up with the same email or mobile number.
8. THE Auth_Service SHALL let the person request a new code at most once a minute and five times an hour per
   email.

### Requirement 2: Email and Password Sign-In

**User Story:** As any HomeFix user, I want to sign in with my email and password in the app I use.

#### Acceptance Criteria

1. THE Auth_Service SHALL accept password sign-in with an identifier that is an email or a username, and
   SHALL treat email case-insensitively.
2. A wrong identifier or password SHALL answer 401 `INVALID_CREDENTIALS` without saying which was wrong; the
   existing lockout after repeated failures SHALL apply per identifier.
3. IF the Account's email is not yet verified, THEN sign-in SHALL answer 403 `EMAIL_NOT_VERIFIED` (only after
   the password is correct) and the app SHALL offer to resend the code.
4. Disabled accounts SHALL keep answering 403 `ACCOUNT_DISABLED` (existing rule).
5. THE customer app and the provider app SHALL offer email sign-in alongside mobile OTP and social sign-in;
   THE Admin Portal SHALL accept an email or a username in its password sign-in.

### Requirement 3: Password Reset

**User Story:** As a user who forgot my password, I want to reset it by email, so that I can get back in.

#### Acceptance Criteria

1. WHEN a user requests a reset for an email, THE Auth_Service SHALL email a Verification_Code if an active
   Account has that verified email, and SHALL answer 202 in every case (no account enumeration).
2. WHEN the correct code and a new password meeting Requirement 1.2 are submitted, THE Auth_Service SHALL
   replace the password, revoke every refresh-token family of the Account, and answer 204.
3. THE reset code SHALL be single-use, expire after 10 minutes and allow at most 5 wrong attempts.

### Requirement 4: Adding Email and Password to an Existing Account

**User Story:** As someone who signed up with mobile OTP, I want to add an email and password, so that I can
also sign in with them.

#### Acceptance Criteria

1. A signed-in user SHALL be able to set an email and password on their own Account; the email SHALL be
   verified by a Verification_Code before it is saved, and it SHALL be unique.
2. A user who already has a password SHALL confirm the current password to change email or password.
3. THE customer app and provider app SHALL expose this in the profile screen.

### Requirement 5: Agency Sign-Up

**User Story:** As a service company, I want to register on HomeFix from the Admin Portal, so that I can
manage my team and take requests once approved.

#### Acceptance Criteria

1. THE Admin Portal SHALL offer "Register your agency": the applicant creates an Account (Requirement 1, role
   `CUSTOMER` as a basic person account) or signs in to an existing one, then submits the agency's name,
   contact phone and email, base location, service radius and categories (platform spec Requirement MT-1.2
   validation).
2. THE Provider_Service SHALL record the agency as a Tenant in status `PENDING_APPROVAL` with the applicant as
   its pending administrator; a user SHALL have at most one pending or active agency application.
3. WHILE a Tenant is `PENDING_APPROVAL` or `REJECTED`, it SHALL NOT cover bookings and its applicant SHALL NOT
   hold `TENANT_ADMIN`.
4. WHEN a platform admin approves the application, THE Provider_Service SHALL set the Tenant `ACTIVE` and make
   the applicant its Tenant_Admin (granting `TENANT_ADMIN` through the Auth_Service, platform spec MT-2);
   WHEN the admin rejects it with a reason, the Tenant SHALL become `REJECTED` and the reason SHALL be shown
   to the applicant.
5. THE Admin Portal SHALL show a signed-in applicant their application status (pending, approved — sign in
   again to continue, rejected with reason) instead of an access-denied screen.
6. THE Tenants module SHALL list pending applications for platform admins with approve and reject actions.
7. THE applicant SHALL be emailed when the application is approved or rejected.

### Requirement 6: Staff Invitations

**User Story:** As a super admin, I want to invite staff by email with a role, so that colleagues can join
without me choosing their password.

#### Acceptance Criteria

1. A `SUPER_ADMIN` SHALL be able to invite an email with any Staff_Role; an `ADMIN` SHALL be able to invite
   only `FINANCE_ADMIN`, `DISPATCHER` or `SUPPORT_AGENT`. `SUPER_ADMIN` SHALL never be granted by invitation.
2. THE Auth_Service SHALL email a single-use link containing a random token, store only a hash of the token,
   and expire the invitation after 7 days; re-inviting the same email SHALL replace the open invitation.
3. WHEN the invitee opens the link, THE Admin Portal SHALL show the role and inviter and ask for display
   name, mobile number and password; completing it SHALL create the Account with that role, the email marked
   verified (the link proves control of it), and sign the person in.
4. IF the invited email already belongs to an Account, THEN accepting SHALL require that Account's password
   and SHALL add the role to it.
5. THE Admin Portal Users module SHALL list open invitations with role, inviter and expiry, and allow
   revoking one; a revoked or expired link SHALL answer 410 `INVITATION_EXPIRED`.
6. Every invitation, acceptance and revocation SHALL be logged with the acting user's id.

### Requirement 7: Email Delivery

**User Story:** As an engineer, I want emails sent through one port, so that local runs need no mail server
and production can plug in a provider.

#### Acceptance Criteria

1. THE Auth_Service SHALL send verification codes, reset codes, invitations and agency decisions through an
   email port with a file adapter (Dev_Mail_Log) for local runs and a logging adapter as the default.
2. Emails SHALL never contain the password; codes and invitation links SHALL never be written to application
   logs (only to the Dev_Mail_Log locally).
3. Agency decision emails SHALL be requested by the Provider_Service through an internal Auth_Service
   endpoint (the Auth_Service owns email delivery).

### Requirement 8: Security and Abuse Controls

**User Story:** As the platform owner, I want sign-up and reset to resist abuse.

#### Acceptance Criteria

1. Sign-up, resend, reset request and invitation acceptance SHALL be rate limited per email and per client
   IP; excess requests SHALL answer 429 `TOO_MANY_REQUESTS`.
2. Responses to sign-up and reset requests SHALL NOT reveal whether an email is registered (Requirements 1.3,
   3.1).
3. THE new public endpoints SHALL be reachable without a token only where needed (sign-up, verify, resend,
   reset, invitation lookup/accept) and SHALL be routed like the existing `/auth/**` endpoints.
4. No path in this feature SHALL grant `ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `DISPATCHER` or
   `SUPPORT_AGENT` except an invitation created by an authorised admin (Requirement 6.1).

### Requirement 9: Quality, Migration and Documentation

#### Acceptance Criteria

1. Schema changes SHALL be new Flyway migrations; existing accounts SHALL keep working unchanged (email
   nullable; existing OTP and username sign-in unaffected).
2. Each new rule SHALL have unit and web-layer tests, including enumeration-safety and rate limits.
3. THE end-to-end checks SHALL cover email sign-up, verification from the Dev_Mail_Log, email sign-in, reset,
   an agency application approved into a working Tenant portal, and a staff invitation accepted.
4. `docs/LOCAL_ACCESS.md`, `docs/API_CONTRACTS.md` and the app READMEs SHALL describe the new flows, including
   where to read codes locally.
