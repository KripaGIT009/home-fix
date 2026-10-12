-- Baseline schema for invoice-service (schema "invoice").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE invoice.invoice (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    generated_at timestamp(6) with time zone NOT NULL,
    gross_amount numeric(12,2) NOT NULL,
    invoice_number character varying(32) NOT NULL,
    payment_id uuid NOT NULL,
    platform_fee numeric(12,2) NOT NULL,
    provider_id uuid NOT NULL,
    provider_net_earning numeric(12,2) NOT NULL,
    s3_key character varying(512) NOT NULL
);

CREATE TABLE invoice.invoice_sequence (
    period character varying(7) NOT NULL,
    next_value bigint NOT NULL
);

ALTER TABLE ONLY invoice.invoice
    ADD CONSTRAINT invoice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice.invoice_sequence
    ADD CONSTRAINT invoice_sequence_pkey PRIMARY KEY (period);

ALTER TABLE ONLY invoice.invoice
    ADD CONSTRAINT uk_5vvlr4mmb6jbwiu4dyqwevd0d UNIQUE (payment_id);

ALTER TABLE ONLY invoice.invoice
    ADD CONSTRAINT uk_t6xkdjx1qtd5whp2iljdfn2yj UNIQUE (invoice_number);
