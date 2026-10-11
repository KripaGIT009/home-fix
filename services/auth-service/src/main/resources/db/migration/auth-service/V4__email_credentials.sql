-- Email credentials, email sign-up and staff invitations (email-auth spec, Requirement 1-4, 6, 9.1).
--
-- Purely additive for existing accounts: every new column is nullable, so OTP, social and
-- username accounts keep working unchanged.

-- email is stored lower case; the unique index is on lower(email) all the same, so a row written
-- outside the application cannot create a case-only duplicate.
ALTER TABLE auth.user_account ADD COLUMN email character varying(254);
ALTER TABLE auth.user_account ADD COLUMN email_verified_at timestamp(6) with time zone;
ALTER TABLE auth.user_account ADD COLUMN display_name character varying(80);

-- When the mobile number was proven by OTP. An email sign-up records a mobile number without
-- verifying it, so OTP sign-in must not treat such a number as proof of the account: it detaches
-- the number instead of signing into that account. Every existing number came through OTP (or the
-- local seeder, which stands in for it), so existing rows are backfilled as verified.
ALTER TABLE auth.user_account ADD COLUMN mobile_verified_at timestamp(6) with time zone;
UPDATE auth.user_account SET mobile_verified_at = created_at WHERE mobile_number IS NOT NULL;

CREATE UNIQUE INDEX uq_user_account_email ON auth.user_account (lower(email)) WHERE email IS NOT NULL;

-- PENDING_VERIFICATION (20 characters) is an email sign-up whose code has not been entered yet.
ALTER TABLE auth.user_account ALTER COLUMN status TYPE character varying(24);
ALTER TABLE auth.user_account DROP CONSTRAINT user_account_status_check;
ALTER TABLE auth.user_account
    ADD CONSTRAINT user_account_status_check
        CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'DEACTIVATED'::character varying, 'PENDING_VERIFICATION'::character varying])::text[])));

-- The hourly sweep removes sign-ups never verified within 24 hours.
CREATE INDEX idx_user_account_pending ON auth.user_account (created_at) WHERE status = 'PENDING_VERIFICATION';

-- Staff invitations (Requirement 6). Only the SHA-256 of the emailed token is stored.
CREATE TABLE auth.staff_invitation (
    id uuid NOT NULL,
    email character varying(254) NOT NULL,
    role character varying(32) NOT NULL,
    token_hash character varying(64) NOT NULL,
    invited_by uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    accepted_at timestamp(6) with time zone,
    accepted_user_id uuid,
    revoked_at timestamp(6) with time zone,
    CONSTRAINT staff_invitation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_staff_invitation_token UNIQUE (token_hash),
    CONSTRAINT staff_invitation_role_check
        CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'FINANCE_ADMIN'::character varying, 'DISPATCHER'::character varying, 'SUPPORT_AGENT'::character varying])::text[])))
);

-- At most one open invitation per address; re-inviting closes the previous one first.
CREATE UNIQUE INDEX uq_staff_invitation_open ON auth.staff_invitation (lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
