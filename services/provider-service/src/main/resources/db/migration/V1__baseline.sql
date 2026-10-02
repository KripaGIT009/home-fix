-- Baseline schema for provider-service (schema "provider").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE provider.availability_slot (
    id uuid NOT NULL,
    day_of_week character varying(16) NOT NULL,
    end_hour integer NOT NULL,
    start_hour integer NOT NULL,
    provider_id uuid NOT NULL,
    CONSTRAINT availability_slot_day_of_week_check CHECK (((day_of_week)::text = ANY ((ARRAY['MONDAY'::character varying, 'TUESDAY'::character varying, 'WEDNESDAY'::character varying, 'THURSDAY'::character varying, 'FRIDAY'::character varying, 'SATURDAY'::character varying, 'SUNDAY'::character varying])::text[])))
);

CREATE TABLE provider.provider_category_selection (
    id uuid NOT NULL,
    category_id uuid NOT NULL,
    provider_id uuid NOT NULL
);

CREATE TABLE provider.provider_earning (
    id uuid NOT NULL,
    booking_id uuid,
    booking_reference character varying(64),
    credited_at timestamp(6) with time zone NOT NULL,
    gross numeric(12,2) NOT NULL,
    net numeric(12,2) NOT NULL,
    platform_fee numeric(12,2) NOT NULL,
    provider_id uuid NOT NULL,
    type character varying(32) NOT NULL,
    CONSTRAINT provider_earning_type_check CHECK (((type)::text = ANY ((ARRAY['JOB_CREDIT'::character varying, 'PLATFORM_FEE_DEDUCTION'::character varying, 'PENALTY_DEDUCTION'::character varying])::text[])))
);

CREATE TABLE provider.provider_profile (
    id uuid NOT NULL,
    aggregate_rating numeric(38,2) NOT NULL,
    bank_account_encrypted character varying(255),
    bank_account_verified boolean NOT NULL,
    base_latitude double precision,
    base_longitude double precision,
    created_at timestamp(6) with time zone NOT NULL,
    display_name character varying(100),
    emergency_available boolean NOT NULL,
    service_radius_km integer NOT NULL,
    under_review boolean NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL,
    version bigint,
    wallet_balance numeric(12,2) NOT NULL,
    years_experience integer NOT NULL
);

CREATE TABLE provider.provider_skill_tag (
    provider_id uuid NOT NULL,
    tag character varying(64) NOT NULL
);

CREATE TABLE provider.provider_subcategory_selection (
    selection_id uuid NOT NULL,
    subcategory_id uuid NOT NULL
);

CREATE TABLE provider.settlement (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    bank_account_ref_encrypted character varying(255) NOT NULL,
    completed_at timestamp(6) with time zone,
    provider_id uuid NOT NULL,
    requested_at timestamp(6) with time zone NOT NULL,
    status character varying(16) NOT NULL,
    CONSTRAINT settlement_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);

ALTER TABLE ONLY provider.availability_slot
    ADD CONSTRAINT availability_slot_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider.provider_category_selection
    ADD CONSTRAINT provider_category_selection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider.provider_earning
    ADD CONSTRAINT provider_earning_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider.provider_profile
    ADD CONSTRAINT provider_profile_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider.settlement
    ADD CONSTRAINT settlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY provider.provider_profile
    ADD CONSTRAINT uk_s9a40v3ivboyelx763v8itlwj UNIQUE (user_id);

ALTER TABLE ONLY provider.provider_skill_tag
    ADD CONSTRAINT fkc2syj3ie5pkiia0y857n3qill FOREIGN KEY (provider_id) REFERENCES provider.provider_profile(id);

ALTER TABLE ONLY provider.provider_subcategory_selection
    ADD CONSTRAINT fke46fanv1ydeq4yvl6cft3rkis FOREIGN KEY (selection_id) REFERENCES provider.provider_category_selection(id);

ALTER TABLE ONLY provider.availability_slot
    ADD CONSTRAINT fkpq7jo29q6gtirh20e2aegwgn5 FOREIGN KEY (provider_id) REFERENCES provider.provider_profile(id);

ALTER TABLE ONLY provider.provider_category_selection
    ADD CONSTRAINT fktotwpnwq9yv11pm467t6v51ts FOREIGN KEY (provider_id) REFERENCES provider.provider_profile(id);
