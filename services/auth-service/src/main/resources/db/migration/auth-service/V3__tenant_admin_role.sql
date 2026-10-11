-- TENANT_ADMIN role for agency administrators (multi-tenant spec, Requirement MT-2.1).
--
-- user_account_role_role_check mirrors the Role enum (V1); it is replaced with the same list plus
-- TENANT_ADMIN. Purely additive: every existing row already satisfies the wider check.

ALTER TABLE auth.user_account_role
    DROP CONSTRAINT user_account_role_role_check;

ALTER TABLE auth.user_account_role
    ADD CONSTRAINT user_account_role_role_check
        CHECK (((role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'SERVICE_PROVIDER'::character varying, 'ADMIN'::character varying, 'SUPER_ADMIN'::character varying, 'FINANCE_ADMIN'::character varying, 'DISPATCHER'::character varying, 'SUPPORT_AGENT'::character varying, 'TENANT_ADMIN'::character varying])::text[])));
