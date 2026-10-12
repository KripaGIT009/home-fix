-- Baseline schema for rating-review-service (schema "rating").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE rating.audit_log (
    id uuid NOT NULL,
    action character varying(64) NOT NULL,
    actor_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    entity_id uuid NOT NULL,
    entity_type character varying(32) NOT NULL,
    reason character varying(1000)
);

CREATE TABLE rating.review (
    id uuid NOT NULL,
    is_active boolean NOT NULL,
    behavior_rating integer,
    booking_id uuid NOT NULL,
    is_flagged boolean NOT NULL,
    overall_rating integer NOT NULL,
    pricing_transparency_rating integer,
    quality_rating integer,
    review_text character varying(1000),
    reviewee_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    reviewer_role character varying(16) NOT NULL,
    source_ip character varying(45),
    submitted_at timestamp(6) with time zone NOT NULL,
    timeliness_rating integer,
    CONSTRAINT review_reviewer_role_check CHECK (((reviewer_role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'PROVIDER'::character varying])::text[])))
);

CREATE TABLE rating.review_prompt (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    fulfilled boolean NOT NULL,
    payment_id uuid NOT NULL,
    reviewee_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    reviewer_role character varying(16) NOT NULL,
    CONSTRAINT review_prompt_reviewer_role_check CHECK (((reviewer_role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'PROVIDER'::character varying])::text[])))
);

ALTER TABLE ONLY rating.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rating.review
    ADD CONSTRAINT review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rating.review_prompt
    ADD CONSTRAINT review_prompt_pkey PRIMARY KEY (id);
