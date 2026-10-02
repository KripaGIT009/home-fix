-- Baseline schema for admin-service (schema "admin").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE admin.audit_log (
    id uuid NOT NULL,
    action_type character varying(32) NOT NULL,
    actor_id uuid NOT NULL,
    after_values text,
    before_values text,
    entity_id character varying(255),
    entity_type character varying(128) NOT NULL,
    logged_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT audit_log_action_type_check CHECK (((action_type)::text = ANY ((ARRAY['CREATE'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying, 'APPROVE'::character varying, 'REJECT'::character varying])::text[])))
);

ALTER TABLE ONLY admin.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);
