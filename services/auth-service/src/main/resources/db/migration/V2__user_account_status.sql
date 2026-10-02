-- Account status for the Admin Portal's User Management screen (Requirement 19.2).
--
-- Every existing account is backfilled ACTIVE through the column default, and the default stays
-- so rows inserted outside the application (docker/seed-*.sh) are ACTIVE too. The check
-- constraint mirrors the AccountStatus enum, the same way user_account_role_role_check mirrors
-- Role in V1.

ALTER TABLE auth.user_account
    ADD COLUMN status character varying(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE auth.user_account
    ADD CONSTRAINT user_account_status_check
        CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'DEACTIVATED'::character varying])::text[])));

-- The admin user list is ordered newest first and capped at 200 rows.
CREATE INDEX idx_user_account_created_at ON auth.user_account (created_at DESC);
