-- Seed a demo Tenant (service agency) for the local HomeFix stack (Requirement MT-15.5).
--
-- "Ara Home Services" covers the seeded Ara service area (20 km around Provider One's base) for every
-- active catalog category, is administered by the `tenantadmin` dev account (+919000000031, TENANT_ADMIN,
-- created by auth-service's DevAccountSeeder) and has the two seeded providers as its team. A booking
-- that automatic matching cannot place in Ara is then routed to this Tenant's assignment queue, where
-- `tenantadmin` assigns one of the two providers from the Tenant Portal (Admin Portal, :5175).
--
-- Prerequisites: seed-catalog.sql, seed-test-users.sh / the auth DevAccountSeeder, seed-provider-profiles.sql
-- have run, and provider-service has started once so migration V3 (tenants) has created its tables.
--
-- Idempotent: the Tenant has a fixed id; re-running leaves existing rows untouched, adds categories that
-- appeared since, and never moves a provider that already belongs to another Tenant.
--   Usage: bash -c '. docker/local-infra.sh && "$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-tenants.sql'

INSERT INTO provider.tenant (
    id, name, status, contact_phone, contact_email,
    base_latitude, base_longitude, service_radius_km, created_at, updated_at, version)
VALUES ('7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6', 'Ara Home Services', 'ACTIVE', '+919000000031',
        'ops@arahomeservices.example', 25.5560, 84.6603, 20.0, now(), now(), 0)
ON CONFLICT (id) DO NOTHING;

-- Every active catalog category, so the demo Tenant covers whatever the customer books in Ara.
INSERT INTO provider.tenant_category (tenant_id, category_id)
SELECT '7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6', c.id
FROM catalog.service_category c
WHERE c.is_active
ON CONFLICT DO NOTHING;

-- The Tenant's administrator, looked up by mobile number because seeding mints fresh user ids.
INSERT INTO provider.tenant_admin (tenant_id, user_id)
SELECT '7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6', u.id
FROM auth.user_account u
WHERE u.mobile_number = '+919000000031'
ON CONFLICT DO NOTHING;

-- The seeded providers join the team unless they already belong to a Tenant.
UPDATE provider.provider_profile p
SET tenant_id = '7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6', updated_at = now()
FROM auth.user_account u
WHERE p.id = u.id
  AND u.mobile_number IN ('+919000000011', '+919000000012')
  AND p.tenant_id IS NULL;
