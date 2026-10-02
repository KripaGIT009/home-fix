-- Reference DDL for the Pricing Engine's tables, valid on PostgreSQL and on H2 in PostgreSQL mode.
-- JpaPricingParametersAdapterTest loads it and boots Hibernate with ddl-auto=validate against it,
-- so it cannot drift from PricingParametersEntity unnoticed. Use it as the source for the
-- service's first schema migration.
CREATE SCHEMA IF NOT EXISTS pricing;

CREATE TABLE IF NOT EXISTS pricing.pricing_parameters (
    subcategory_id       uuid                        NOT NULL,
    base_price           numeric(19,4)               NOT NULL,
    per_km_rate          numeric(19,4),
    max_travel_charge    numeric(19,4),
    night_surcharge      numeric(19,4),
    weekend_surcharge    numeric(19,4),
    platform_fee_rate    numeric(9,6),
    tax_rate             numeric(9,6),
    emergency_multiplier numeric(9,4),
    surge_multiplier     numeric(9,4),
    override_floor       numeric(19,4),
    override_ceiling     numeric(19,4),
    created_at           timestamp(6) with time zone NOT NULL,
    updated_at           timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (subcategory_id)
);
