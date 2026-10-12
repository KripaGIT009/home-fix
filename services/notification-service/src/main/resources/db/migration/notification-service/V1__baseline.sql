-- Baseline schema for notification-service (schema "notification").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE notification.delivery_log (
    channel character varying(20) NOT NULL,
    kafka_event_id uuid NOT NULL,
    user_id uuid NOT NULL,
    delivery_status character varying(30) NOT NULL,
    error_description character varying(500),
    retry_count integer NOT NULL,
    delivered_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT delivery_log_channel_check CHECK (((channel)::text = ANY ((ARRAY['PUSH'::character varying, 'SMS'::character varying, 'EMAIL'::character varying, 'IN_APP'::character varying])::text[]))),
    CONSTRAINT delivery_log_delivery_status_check CHECK (((delivery_status)::text = ANY ((ARRAY['DELIVERED'::character varying, 'PERMANENTLY_FAILED'::character varying, 'SKIPPED_PREFERENCE'::character varying, 'SKIPPED_NO_CONTACT'::character varying])::text[])))
);

ALTER TABLE ONLY notification.delivery_log
    ADD CONSTRAINT delivery_log_pkey PRIMARY KEY (channel, kafka_event_id, user_id);
