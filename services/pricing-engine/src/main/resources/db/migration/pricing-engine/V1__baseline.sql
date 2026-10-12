-- Baseline schema for pricing-engine (schema "pricing").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE pricing.pricing_parameters (
    subcategory_id uuid NOT NULL,
    base_price numeric(19,4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    emergency_multiplier numeric(9,4),
    max_travel_charge numeric(19,4),
    night_surcharge numeric(19,4),
    override_ceiling numeric(19,4),
    override_floor numeric(19,4),
    per_km_rate numeric(19,4),
    platform_fee_rate numeric(9,6),
    surge_multiplier numeric(9,4),
    tax_rate numeric(9,6),
    updated_at timestamp(6) with time zone NOT NULL,
    weekend_surcharge numeric(19,4)
);

ALTER TABLE ONLY pricing.pricing_parameters
    ADD CONSTRAINT pricing_parameters_pkey PRIMARY KEY (subcategory_id);
