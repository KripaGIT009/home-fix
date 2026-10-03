-- Tenants: service agencies that own a team of Providers and cover a service area for a set of
-- service categories (Requirements MT-1, MT-2, MT-3). Additive only: existing providers stay
-- independent (tenant_id NULL) and need no backfill (Requirement MT-15.2).

CREATE TABLE provider.tenant (
    id                uuid PRIMARY KEY,
    name              varchar(120)     NOT NULL,
    status            varchar(16)      NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT tenant_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    contact_phone     varchar(20),
    contact_email     varchar(254),
    base_latitude     double precision NOT NULL,
    base_longitude    double precision NOT NULL,
    service_radius_km numeric(5, 1)    NOT NULL
        CONSTRAINT tenant_service_radius_check CHECK (service_radius_km BETWEEN 1 AND 100),
    created_at        timestamptz      NOT NULL,
    updated_at        timestamptz      NOT NULL,
    -- The last Platform_Admin to change the Tenant (Requirement MT-1.3).
    updated_by        uuid,
    version           bigint           NOT NULL
);

CREATE TABLE provider.tenant_category (
    tenant_id   uuid NOT NULL REFERENCES provider.tenant (id),
    category_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, category_id)
);

-- The primary key on user_id is the guarantee that a user administers at most one Tenant
-- (Requirement MT-2.3, Property MT8), whatever races the service loses.
CREATE TABLE provider.tenant_admin (
    tenant_id uuid NOT NULL REFERENCES provider.tenant (id),
    user_id   uuid PRIMARY KEY
);

CREATE INDEX idx_tenant_admin_tenant ON provider.tenant_admin (tenant_id);

-- A single nullable column, so a Provider belongs to at most one Tenant by construction
-- (Requirement MT-3.1, Property MT8).
ALTER TABLE provider.provider_profile ADD COLUMN tenant_id uuid REFERENCES provider.tenant (id);

CREATE INDEX idx_provider_profile_tenant ON provider.provider_profile (tenant_id);
