-- Baseline schema for customer-service (schema "customer").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE customer.address (
    id uuid NOT NULL,
    address_text_encrypted character varying(4096),
    created_at timestamp(6) with time zone NOT NULL,
    customer_id uuid NOT NULL,
    is_active boolean NOT NULL,
    is_default boolean NOT NULL,
    label character varying(100),
    lat double precision NOT NULL,
    lng double precision NOT NULL
);

CREATE TABLE customer.customer_profile (
    id uuid NOT NULL,
    anonymized boolean NOT NULL,
    display_name_encrypted character varying(1024),
    email_encrypted character varying(1024),
    photo_url character varying(2048),
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE customer.deletion_request (
    id uuid NOT NULL,
    acknowledged_at timestamp(6) with time zone NOT NULL,
    anonymize_after timestamp(6) with time zone NOT NULL,
    anonymized_at timestamp(6) with time zone,
    customer_id uuid NOT NULL,
    requested_at timestamp(6) with time zone NOT NULL,
    status character varying(32) NOT NULL,
    CONSTRAINT deletion_request_status_check CHECK (((status)::text = ANY ((ARRAY['ACKNOWLEDGED'::character varying, 'ANONYMIZED'::character varying])::text[])))
);

ALTER TABLE ONLY customer.address
    ADD CONSTRAINT address_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer.customer_profile
    ADD CONSTRAINT customer_profile_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer.deletion_request
    ADD CONSTRAINT deletion_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customer.customer_profile
    ADD CONSTRAINT uk_ebvildrqpv1sbeq1jw7k9kykh UNIQUE (user_id);
