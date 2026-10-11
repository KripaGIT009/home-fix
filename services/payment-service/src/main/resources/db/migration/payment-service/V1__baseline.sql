-- Baseline schema for payment-service (schema "payment").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE payment.payment_refund (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    failure_reason character varying(512),
    gateway_reference character varying(128),
    idempotency_key character varying(128) NOT NULL,
    status character varying(16) NOT NULL,
    transaction_id uuid NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint,
    CONSTRAINT payment_refund_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE payment.payment_settlement (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    bank_account_ref_encrypted character varying(255) NOT NULL,
    failure_reason character varying(512),
    gateway_reference character varying(128),
    provider_id uuid NOT NULL,
    requested_at timestamp(6) with time zone NOT NULL,
    status character varying(16) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint,
    CONSTRAINT payment_settlement_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE payment.payment_transaction (
    id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    attempt_count integer NOT NULL,
    booking_id uuid NOT NULL,
    callback_event_id character varying(128),
    created_at timestamp(6) with time zone NOT NULL,
    customer_id uuid NOT NULL,
    failure_reason character varying(512),
    gateway character varying(32) NOT NULL,
    gateway_reference character varying(128),
    idempotency_key character varying(128) NOT NULL,
    method character varying(24) NOT NULL,
    payment_credential_encrypted character varying(255),
    platform_fee numeric(12,2) NOT NULL,
    provider_id uuid NOT NULL,
    refunded_amount numeric(12,2) NOT NULL,
    status character varying(24) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint,
    wallet_credit_pending_since timestamp(6) with time zone,
    CONSTRAINT payment_transaction_method_check CHECK (((method)::text = ANY ((ARRAY['UPI'::character varying, 'CREDIT_DEBIT_CARD'::character varying, 'NET_BANKING'::character varying, 'WALLET'::character varying, 'CASH'::character varying])::text[]))),
    CONSTRAINT payment_transaction_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'REFUNDED'::character varying, 'PARTIALLY_REFUNDED'::character varying])::text[])))
);

ALTER TABLE ONLY payment.payment_refund
    ADD CONSTRAINT payment_refund_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment.payment_settlement
    ADD CONSTRAINT payment_settlement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment.payment_transaction
    ADD CONSTRAINT payment_transaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment.payment_transaction
    ADD CONSTRAINT uk_48aiw2plynjrh8d5wu2laxjij UNIQUE (idempotency_key);

ALTER TABLE ONLY payment.payment_refund
    ADD CONSTRAINT uk_rh07rkcnwpfgqsb0cmovl2250 UNIQUE (idempotency_key);

CREATE INDEX ix_payment_refund_transaction ON payment.payment_refund USING btree (transaction_id);
