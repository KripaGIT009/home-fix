-- Baseline schema for verification-service (schema "verification").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE verification.verification (
    id uuid NOT NULL,
    background_check_result character varying(2000),
    background_check_started_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    provider_id uuid NOT NULL,
    status character varying(40) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint,
    CONSTRAINT verification_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DOCUMENT_SUBMITTED'::character varying, 'DOCUMENT_VERIFIED'::character varying, 'BACKGROUND_CHECK_PENDING'::character varying, 'BACKGROUND_CHECK_COMPLETED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'SUSPENDED'::character varying])::text[])))
);

CREATE TABLE verification.verification_audit_entry (
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    from_state character varying(40) NOT NULL,
    reason character varying(1000),
    sequence bigint NOT NULL,
    to_state character varying(40) NOT NULL,
    verification_id uuid NOT NULL,
    CONSTRAINT verification_audit_entry_from_state_check CHECK (((from_state)::text = ANY ((ARRAY['PENDING'::character varying, 'DOCUMENT_SUBMITTED'::character varying, 'DOCUMENT_VERIFIED'::character varying, 'BACKGROUND_CHECK_PENDING'::character varying, 'BACKGROUND_CHECK_COMPLETED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT verification_audit_entry_to_state_check CHECK (((to_state)::text = ANY ((ARRAY['PENDING'::character varying, 'DOCUMENT_SUBMITTED'::character varying, 'DOCUMENT_VERIFIED'::character varying, 'BACKGROUND_CHECK_PENDING'::character varying, 'BACKGROUND_CHECK_COMPLETED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'SUSPENDED'::character varying])::text[])))
);

CREATE TABLE verification.verification_document (
    id uuid NOT NULL,
    content_type character varying(100),
    document_type character varying(40) NOT NULL,
    size_bytes bigint,
    storage_ref character varying(512) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL,
    verification_id uuid NOT NULL,
    CONSTRAINT verification_document_document_type_check CHECK (((document_type)::text = ANY ((ARRAY['GOVERNMENT_ID'::character varying, 'ADDRESS_PROOF'::character varying, 'SKILL_CERTIFICATION'::character varying])::text[])))
);

ALTER TABLE ONLY verification.verification
    ADD CONSTRAINT uk_stf8egx6ouovi45m5qkisbuxx UNIQUE (provider_id);

ALTER TABLE ONLY verification.verification_audit_entry
    ADD CONSTRAINT verification_audit_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY verification.verification_document
    ADD CONSTRAINT verification_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY verification.verification
    ADD CONSTRAINT verification_pkey PRIMARY KEY (id);

ALTER TABLE ONLY verification.verification_document
    ADD CONSTRAINT fkeybwg9xtsk9kedtd3bkeghvfk FOREIGN KEY (verification_id) REFERENCES verification.verification(id);

ALTER TABLE ONLY verification.verification_audit_entry
    ADD CONSTRAINT fkn24jpmjly0838encnbm29j1j5 FOREIGN KEY (verification_id) REFERENCES verification.verification(id);
