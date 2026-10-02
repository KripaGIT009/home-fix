-- Baseline schema for promotion-service (schema "promotion").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE promotion.coupon (
    id uuid NOT NULL,
    active boolean NOT NULL,
    code character varying(20) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    discount_type character varying(16) NOT NULL,
    discount_value numeric(12,2) NOT NULL,
    expiry_date date NOT NULL,
    max_discount_cap numeric(12,2),
    min_order_value numeric(12,2) NOT NULL,
    per_user_limit integer NOT NULL,
    total_limit integer NOT NULL,
    total_used integer NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    valid_from date NOT NULL,
    version bigint,
    CONSTRAINT coupon_discount_type_check CHECK (((discount_type)::text = ANY ((ARRAY['FLAT'::character varying, 'PERCENTAGE'::character varying])::text[])))
);

CREATE TABLE promotion.coupon_usage (
    id uuid NOT NULL,
    coupon_id uuid NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    usage_count integer NOT NULL,
    user_id uuid NOT NULL,
    version bigint
);

ALTER TABLE ONLY promotion.coupon
    ADD CONSTRAINT coupon_pkey PRIMARY KEY (id);

ALTER TABLE ONLY promotion.coupon_usage
    ADD CONSTRAINT coupon_usage_pkey PRIMARY KEY (id);

ALTER TABLE ONLY promotion.coupon
    ADD CONSTRAINT uk_bg4p9ontpj7adq7yr71h93sdn UNIQUE (code);

ALTER TABLE ONLY promotion.coupon_usage
    ADD CONSTRAINT uk_coupon_usage_coupon_user UNIQUE (coupon_id, user_id);
