-- Baseline schema for catalog-service (schema "catalog").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE catalog.service_category (
    id uuid NOT NULL,
    is_active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description character varying(1000),
    display_order integer NOT NULL,
    icon_url character varying(512),
    name character varying(120) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint
);

CREATE TABLE catalog.service_subcategory (
    id uuid NOT NULL,
    is_active boolean NOT NULL,
    base_price numeric(10,2) NOT NULL,
    category_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    emergency_available boolean NOT NULL,
    estimated_duration_min integer NOT NULL,
    name character varying(120) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint
);

CREATE TABLE catalog.service_subcategory_skill_tag (
    subcategory_id uuid NOT NULL,
    tag character varying(64) NOT NULL
);

ALTER TABLE ONLY catalog.service_category
    ADD CONSTRAINT service_category_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.service_subcategory
    ADD CONSTRAINT service_subcategory_pkey PRIMARY KEY (id);

ALTER TABLE ONLY catalog.service_subcategory_skill_tag
    ADD CONSTRAINT fkt9miinvpalvgcc4iy9y2rmo6t FOREIGN KEY (subcategory_id) REFERENCES catalog.service_subcategory(id);
