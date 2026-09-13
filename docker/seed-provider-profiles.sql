-- Seed provider profiles for the local HomeFix core slice.
--
-- seed-test-users.sh creates auth accounts only. A provider profile is otherwise created lazily by
-- PUT /providers/{id}/profile (the onboarding screen), so until somebody completes onboarding the
-- provider app answers PROVIDER_NOT_FOUND on every provider screen. This seeds the two test
-- providers so the app has something to show.
--
-- Ids are looked up from auth.user_account by mobile number rather than hard-coded, because
-- seed-test-users.sh mints fresh uuids on every clean database.
--
-- The profile id is deliberately the same value as the user id: ProviderProfile.createWithId pins
-- them together, and CallerIdentity compares the {id} path segment against the JWT subject, so a
-- provider can only reach their own record when the two match.
--
-- Idempotent: re-running leaves existing rows untouched.
--   Usage: docker exec -i homefix-core-postgres-1 psql -U homefix -d homefix < docker/seed-provider-profiles.sql

INSERT INTO provider.provider_profile (
    id, user_id, display_name, years_experience, service_radius_km,
    aggregate_rating, wallet_balance, emergency_available, under_review,
    bank_account_verified, created_at, updated_at, version)
SELECT u.id, u.id, s.display_name, s.years_experience, s.radius_km,
       s.rating, s.wallet, s.emergency, false, false, now(), now(), 0
FROM (VALUES
        ('+919000000011', 'Provider One', 6, 15, 4.60, 2450.00, true),
        ('+919000000012', 'Provider Two', 3, 10, 4.20,  980.00, false)
     ) AS s(mobile, display_name, years_experience, radius_km, rating, wallet, emergency)
JOIN auth.user_account u ON u.mobile_number = s.mobile
ON CONFLICT (id) DO NOTHING;

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
