-- Baseline schema for auth-service (schema "auth").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE auth.social_identity_link (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    provider character varying(16) NOT NULL,
    provider_subject character varying(255) NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT social_identity_link_provider_check CHECK (((provider)::text = ANY ((ARRAY['GOOGLE'::character varying, 'APPLE'::character varying])::text[])))
);

CREATE TABLE auth.user_account (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    mobile_number character varying(20),
    password_hash character varying(100),
    username character varying(64),
    verified boolean NOT NULL
);

CREATE TABLE auth.user_account_role (
    user_id uuid NOT NULL,
    role character varying(32) NOT NULL,
    CONSTRAINT user_account_role_role_check CHECK (((role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'SERVICE_PROVIDER'::character varying, 'ADMIN'::character varying, 'SUPER_ADMIN'::character varying, 'FINANCE_ADMIN'::character varying, 'DISPATCHER'::character varying, 'SUPPORT_AGENT'::character varying])::text[])))
);

ALTER TABLE ONLY auth.social_identity_link
    ADD CONSTRAINT social_identity_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY auth.user_account
    ADD CONSTRAINT uk_1iiabmv7vr1f9nem2voqxsep4 UNIQUE (mobile_number);

ALTER TABLE ONLY auth.user_account
    ADD CONSTRAINT uk_castjbvpeeus0r8lbpehiu0e4 UNIQUE (username);

ALTER TABLE ONLY auth.social_identity_link
    ADD CONSTRAINT uq_social_identity_provider_subject UNIQUE (provider, provider_subject);

ALTER TABLE ONLY auth.user_account
    ADD CONSTRAINT user_account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY auth.user_account_role
    ADD CONSTRAINT user_account_role_pkey PRIMARY KEY (user_id, role);

ALTER TABLE ONLY auth.user_account_role
    ADD CONSTRAINT fkq277frry9oav5eajxj8lykuit FOREIGN KEY (user_id) REFERENCES auth.user_account(id);
