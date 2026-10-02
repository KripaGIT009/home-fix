-- Seed provider profiles for the local HomeFix core slice.
--
-- seed-test-users.sh creates auth accounts only. A provider profile is otherwise created lazily by
-- PUT /providers/{id}/profile (the onboarding screen), so until somebody completes onboarding the
-- provider app answers PROVIDER_NOT_FOUND on every provider screen. This seeds the two test
-- providers so the app has something to show, and makes them dispatchable: the Dispatch Engine's
-- GET /internal/providers/eligible only returns a provider who has a base service location within
-- range, shares a skill tag with the booked subcategory (docker/seed-catalog.sql), and is APPROVED
-- in the Verification Service.
--
-- Ids are looked up from auth.user_account by mobile number rather than hard-coded, because
-- seed-test-users.sh mints fresh uuids on every clean database.
--
-- The profile id is deliberately the same value as the user id: ProviderProfile.createWithId pins
-- them together, and CallerIdentity compares the {id} path segment against the JWT subject, so a
-- provider can only reach their own record when the two match. The verification record is keyed
-- by the same id (Verification.providerId).
--
-- Prerequisites: docker/seed-catalog.sql has run (category selections are looked up by name), and
-- provider-service and verification-service have each started once, so ddl-auto=update has
-- created their tables and the base_latitude/base_longitude columns.
--
-- Idempotent: re-running leaves existing rows untouched. The one exception is the base location,
-- which is filled in on an existing profile that has none (profiles seeded before the column
-- existed), never overwritten.
--   Usage: docker exec -i homefix-core-postgres-1 psql -U homefix -d homefix < docker/seed-provider-profiles.sql

-- Both providers are based in Ara, Bihar (the service area the apps hard-code); Provider Two about
-- a kilometre north-east of Provider One. availability_slot is left empty on purpose: a provider
-- with no schedule is treated as always available by the eligibility search.
INSERT INTO provider.provider_profile (
    id, user_id, display_name, years_experience, service_radius_km,
    aggregate_rating, wallet_balance, emergency_available, under_review,
    bank_account_verified, base_latitude, base_longitude, created_at, updated_at, version)
SELECT u.id, u.id, s.display_name, s.years_experience, s.radius_km,
       s.rating, s.wallet, s.emergency, false, false, s.lat, s.lon, now(), now(), 0
FROM (VALUES
        ('+919000000011', 'Provider One', 6, 15, 4.60, 2450.00, true,  25.5560, 84.6603),
        ('+919000000012', 'Provider Two', 3, 10, 4.20,  980.00, false, 25.5620, 84.6680)
     ) AS s(mobile, display_name, years_experience, radius_km, rating, wallet, emergency, lat, lon)
JOIN auth.user_account u ON u.mobile_number = s.mobile
ON CONFLICT (id) DO NOTHING;

-- Backfill the base location on profiles seeded before it existed. Only where both are unset, so
-- a location the provider has since chosen is never overwritten.
UPDATE provider.provider_profile p
SET base_latitude = s.lat, base_longitude = s.lon, updated_at = now()
FROM (VALUES
        ('+919000000011', 25.5560, 84.6603),
        ('+919000000012', 25.5620, 84.6680)
     ) AS s(mobile, lat, lon)
JOIN auth.user_account u ON u.mobile_number = s.mobile
WHERE p.id = u.id
  AND p.base_latitude IS NULL AND p.base_longitude IS NULL;

-- Skill tags are the ones docker/seed-catalog.sql puts on service_subcategory_skill_tag, which is
-- what the Dispatch Engine matches against.
INSERT INTO provider.provider_skill_tag (provider_id, tag)
SELECT u.id, s.tag
FROM (VALUES
        ('+919000000011', 'plumbing'),
        ('+919000000011', 'electrical'),
        ('+919000000012', 'cleaning')
     ) AS s(mobile, tag)
JOIN auth.user_account u ON u.mobile_number = s.mobile
JOIN provider.provider_profile p ON p.id = u.id
WHERE NOT EXISTS (
    SELECT 1 FROM provider.provider_skill_tag existing
    WHERE existing.provider_id = u.id AND existing.tag = s.tag);

-- Category selections matching the tags, so the profile screen shows what each provider offers.
-- Keyed by catalog category name, since seed-catalog.sql mints fresh category ids.
INSERT INTO provider.provider_category_selection (id, provider_id, category_id)
SELECT gen_random_uuid(), u.id, c.id
FROM (VALUES
        ('+919000000011', 'Plumbing'),
        ('+919000000011', 'Electrical'),
        ('+919000000012', 'Cleaning')
     ) AS s(mobile, category)
JOIN auth.user_account u ON u.mobile_number = s.mobile
JOIN provider.provider_profile p ON p.id = u.id
JOIN catalog.service_category c ON c.name = s.category
WHERE NOT EXISTS (
    SELECT 1 FROM provider.provider_category_selection existing
    WHERE existing.provider_id = u.id AND existing.category_id = c.id);

-- Every active subcategory of each selected category (the catalog has two per category, well
-- under the ten-per-category limit), for the two seeded providers only.
INSERT INTO provider.provider_subcategory_selection (selection_id, subcategory_id)
SELECT sel.id, sub.id
FROM provider.provider_category_selection sel
JOIN auth.user_account u ON u.id = sel.provider_id
JOIN catalog.service_subcategory sub ON sub.category_id = sel.category_id AND sub.is_active
WHERE u.mobile_number IN ('+919000000011', '+919000000012')
  AND NOT EXISTS (
    SELECT 1 FROM provider.provider_subcategory_selection existing
    WHERE existing.selection_id = sel.id AND existing.subcategory_id = sub.id);

-- Verification: only APPROVED providers are dispatchable (Requirements 5.10, 8.2). Without these
-- rows the test providers would have to walk the whole document/background-check flow through
-- the admin portal first. A provider who already has a verification record keeps it, whatever
-- its state.
INSERT INTO verification.verification (id, provider_id, status, created_at, updated_at, version)
SELECT gen_random_uuid(), u.id, 'APPROVED', now(), now(), 0
FROM auth.user_account u
WHERE u.mobile_number IN ('+919000000011', '+919000000012')
  AND NOT EXISTS (
    SELECT 1 FROM verification.verification existing WHERE existing.provider_id = u.id);

-- One audit entry per seeded approval, so the trail explains how the record became APPROVED
-- (Requirement 5.11) and the next real transition continues the sequence at 1. The transition
-- recorded is the state machine's own BACKGROUND_CHECK_COMPLETED -> APPROVED; the actor is the
-- nil uuid, which no real account has.
INSERT INTO verification.verification_audit_entry (
    id, verification_id, sequence, from_state, to_state, actor_id, reason, created_at)
SELECT gen_random_uuid(), v.id, 0, 'BACKGROUND_CHECK_COMPLETED', 'APPROVED',
       '00000000-0000-0000-0000-000000000000', 'Seeded as APPROVED for local development', now()
FROM verification.verification v
JOIN auth.user_account u ON u.id = v.provider_id
WHERE u.mobile_number IN ('+919000000011', '+919000000012')
  AND v.status = 'APPROVED'
  AND NOT EXISTS (
    SELECT 1 FROM verification.verification_audit_entry existing WHERE existing.verification_id = v.id);
