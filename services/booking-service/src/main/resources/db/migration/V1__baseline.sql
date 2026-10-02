-- Baseline schema for booking-service (schema "booking").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE booking.booking (
    id uuid NOT NULL,
    address_id uuid,
    cancellation_fee numeric(12,2),
    category_id uuid NOT NULL,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    customer_id uuid NOT NULL,
    is_emergency boolean NOT NULL,
    estimated_total numeric(12,2),
    final_total numeric(12,2),
    net_duration_seconds integer,
    provider_id uuid,
    reference character varying(40) NOT NULL,
    saga_state character varying(60),
    scheduled_at timestamp(6) with time zone,
    started_at timestamp(6) with time zone,
    status character varying(40) NOT NULL,
    subcategory_id uuid NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT booking_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'SEARCHING_PROVIDER'::character varying, 'SEARCHING_FAILED'::character varying, 'PROVIDER_ASSIGNED'::character varying, 'PROVIDER_ACCEPTED'::character varying, 'PROVIDER_ON_THE_WAY'::character varying, 'PROVIDER_ARRIVED'::character varying, 'JOB_STARTED'::character varying, 'JOB_PAUSED'::character varying, 'ADDITIONAL_QUOTE_REQUIRED'::character varying, 'CUSTOMER_APPROVAL_PENDING'::character varying, 'JOB_COMPLETED'::character varying, 'CUSTOMER_CONFIRMED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_COMPLETED'::character varying, 'DISPUTED'::character varying, 'REFUNDED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE booking.booking_audit (
    id uuid NOT NULL,
    actor_id uuid,
    actor_role character varying(60) NOT NULL,
    booking_id uuid NOT NULL,
    from_state character varying(40),
    reason character varying(500),
    to_state character varying(40) NOT NULL,
    transitioned_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT booking_audit_from_state_check CHECK (((from_state)::text = ANY ((ARRAY['CREATED'::character varying, 'SEARCHING_PROVIDER'::character varying, 'SEARCHING_FAILED'::character varying, 'PROVIDER_ASSIGNED'::character varying, 'PROVIDER_ACCEPTED'::character varying, 'PROVIDER_ON_THE_WAY'::character varying, 'PROVIDER_ARRIVED'::character varying, 'JOB_STARTED'::character varying, 'JOB_PAUSED'::character varying, 'ADDITIONAL_QUOTE_REQUIRED'::character varying, 'CUSTOMER_APPROVAL_PENDING'::character varying, 'JOB_COMPLETED'::character varying, 'CUSTOMER_CONFIRMED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_COMPLETED'::character varying, 'DISPUTED'::character varying, 'REFUNDED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT booking_audit_to_state_check CHECK (((to_state)::text = ANY ((ARRAY['CREATED'::character varying, 'SEARCHING_PROVIDER'::character varying, 'SEARCHING_FAILED'::character varying, 'PROVIDER_ASSIGNED'::character varying, 'PROVIDER_ACCEPTED'::character varying, 'PROVIDER_ON_THE_WAY'::character varying, 'PROVIDER_ARRIVED'::character varying, 'JOB_STARTED'::character varying, 'JOB_PAUSED'::character varying, 'ADDITIONAL_QUOTE_REQUIRED'::character varying, 'CUSTOMER_APPROVAL_PENDING'::character varying, 'JOB_COMPLETED'::character varying, 'CUSTOMER_CONFIRMED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_COMPLETED'::character varying, 'DISPUTED'::character varying, 'REFUNDED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE booking.job_interval (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    ended_at timestamp(6) with time zone,
    kind character varying(10) NOT NULL,
    reason character varying(500),
    started_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT job_interval_kind_check CHECK (((kind)::text = ANY ((ARRAY['WORK'::character varying, 'PAUSE'::character varying])::text[])))
);

CREATE TABLE booking.job_media (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    content_type character varying(100) NOT NULL,
    s3_key character varying(1024) NOT NULL,
    size_bytes bigint NOT NULL,
    type character varying(40) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE booking.parts_line_item (
    id uuid NOT NULL,
    added_at timestamp(6) with time zone NOT NULL,
    booking_id uuid NOT NULL,
    item_name character varying(200) NOT NULL,
    quantity integer NOT NULL,
    unit_cost numeric(12,2) NOT NULL
);

CREATE TABLE booking.saga_step (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    compensated_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    detail character varying(2000),
    recorded_at timestamp(6) with time zone NOT NULL,
    sequence_no integer NOT NULL,
    status character varying(20) NOT NULL,
    step_name character varying(80) NOT NULL,
    CONSTRAINT saga_step_status_check CHECK (((status)::text = ANY ((ARRAY['STARTED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'COMPENSATED'::character varying])::text[])))
);

ALTER TABLE ONLY booking.booking_audit
    ADD CONSTRAINT booking_audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking.booking
    ADD CONSTRAINT booking_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking.booking
    ADD CONSTRAINT idx_booking_reference UNIQUE (reference);

ALTER TABLE ONLY booking.job_interval
    ADD CONSTRAINT job_interval_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking.job_media
    ADD CONSTRAINT job_media_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking.parts_line_item
    ADD CONSTRAINT parts_line_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking.saga_step
    ADD CONSTRAINT saga_step_pkey PRIMARY KEY (id);

CREATE INDEX idx_booking_audit_booking ON booking.booking_audit USING btree (booking_id, transitioned_at);

CREATE INDEX idx_booking_customer ON booking.booking USING btree (customer_id);

CREATE INDEX idx_job_interval_booking ON booking.job_interval USING btree (booking_id, started_at);

CREATE INDEX idx_job_media_booking ON booking.job_media USING btree (booking_id);

CREATE INDEX idx_parts_booking ON booking.parts_line_item USING btree (booking_id);

CREATE INDEX idx_saga_step_booking ON booking.saga_step USING btree (booking_id, sequence_no);
