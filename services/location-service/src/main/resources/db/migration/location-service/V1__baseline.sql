-- Baseline schema for location-service (schema "location").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE location.location_history (
    id uuid NOT NULL,
    booking_id uuid NOT NULL,
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,
    provider_id uuid NOT NULL,
    recorded_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE location.terminated_booking (
    booking_id uuid NOT NULL,
    terminated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY location.location_history
    ADD CONSTRAINT location_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY location.terminated_booking
    ADD CONSTRAINT terminated_booking_pkey PRIMARY KEY (booking_id);

CREATE INDEX idx_location_history_booking ON location.location_history USING btree (booking_id, recorded_at);
