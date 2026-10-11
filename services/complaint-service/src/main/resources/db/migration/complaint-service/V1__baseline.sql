-- Baseline schema for complaint-service (schema "complaint").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE complaint.complaint (
    id uuid NOT NULL,
    acknowledged boolean NOT NULL,
    agent_id uuid,
    booking_id uuid NOT NULL,
    category character varying(32) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    customer_id uuid NOT NULL,
    description character varying(2000) NOT NULL,
    escalated boolean NOT NULL,
    priority character varying(16) NOT NULL,
    provider_id uuid,
    resolved_at timestamp(6) with time zone,
    settlement_held boolean NOT NULL,
    sla_deadline timestamp(6) with time zone NOT NULL,
    status character varying(24) NOT NULL,
    CONSTRAINT complaint_category_check CHECK (((category)::text = ANY ((ARRAY['POOR_QUALITY'::character varying, 'LATE_ARRIVAL'::character varying, 'OVERCHARGING'::character varying, 'UNPROFESSIONAL_BEHAVIOR'::character varying, 'INCOMPLETE_WORK'::character varying, 'DAMAGE'::character varying, 'PAYMENT_ISSUE'::character varying])::text[]))),
    CONSTRAINT complaint_priority_check CHECK (((priority)::text = ANY ((ARRAY['EMERGENCY'::character varying, 'STANDARD'::character varying])::text[]))),
    CONSTRAINT complaint_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IN_PROGRESS'::character varying, 'ESCALATED'::character varying, 'DISPUTED'::character varying, 'REFUND_FAILED'::character varying, 'RESOLVED'::character varying, 'CLOSED'::character varying])::text[])))
);

CREATE TABLE complaint.complaint_refund (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    approved_by uuid NOT NULL,
    booking_id uuid NOT NULL,
    client_idempotency_key character varying(64),
    complaint_id uuid NOT NULL,
    completed_at timestamp(6) with time zone,
    external_reference character varying(128),
    failure_reason character varying(500),
    reason character varying(500),
    requested_at timestamp(6) with time zone NOT NULL,
    status character varying(16) NOT NULL,
    version bigint,
    CONSTRAINT complaint_refund_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

ALTER TABLE ONLY complaint.complaint
    ADD CONSTRAINT complaint_pkey PRIMARY KEY (id);

ALTER TABLE ONLY complaint.complaint_refund
    ADD CONSTRAINT complaint_refund_pkey PRIMARY KEY (id);

ALTER TABLE ONLY complaint.complaint_refund
    ADD CONSTRAINT uk_complaint_refund_complaint UNIQUE (complaint_id);
