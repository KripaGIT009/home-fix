-- Seed sample catalog data for the local HomeFix core slice.
-- Categories and subcategories the customer-app Home screen will list.
SET search_path TO catalog;

DO $$
DECLARE
  c_clean  uuid := gen_random_uuid();
  c_plumb  uuid := gen_random_uuid();
  c_elec   uuid := gen_random_uuid();
  c_appl   uuid := gen_random_uuid();
BEGIN
  INSERT INTO service_category (id, name, description, icon_url, display_order, is_active, created_at, updated_at, version) VALUES
    (c_clean, 'Cleaning',      'Home and bathroom cleaning services',        NULL, 1, true, now(), now(), 0),
    (c_plumb, 'Plumbing',      'Taps, leaks, fittings and pipe repairs',     NULL, 2, true, now(), now(), 0),
    (c_elec,  'Electrical',    'Wiring, switches, fans and fixtures',        NULL, 3, true, now(), now(), 0),
    (c_appl,  'Appliance Repair', 'AC, refrigerator and washing machine',    NULL, 4, true, now(), now(), 0);

  INSERT INTO service_subcategory (id, category_id, name, base_price, estimated_duration_min, emergency_available, is_active, created_at, updated_at, version) VALUES
    (gen_random_uuid(), c_clean, 'Full Home Deep Cleaning', 1499.00, 180, false, true, now(), now(), 0),
    (gen_random_uuid(), c_clean, 'Bathroom Cleaning',        499.00,  60, false, true, now(), now(), 0),
    (gen_random_uuid(), c_plumb, 'Tap / Faucet Repair',      299.00,  45, true,  true, now(), now(), 0),
    (gen_random_uuid(), c_plumb, 'Leak Fixing',              399.00,  60, true,  true, now(), now(), 0),
    (gen_random_uuid(), c_elec,  'Fan Installation',         349.00,  45, false, true, now(), now(), 0),
    (gen_random_uuid(), c_elec,  'Switchboard Repair',       299.00,  40, true,  true, now(), now(), 0),
    (gen_random_uuid(), c_appl,  'AC Service',               599.00,  90, false, true, now(), now(), 0),
    (gen_random_uuid(), c_appl,  'Refrigerator Repair',      499.00,  75, false, true, now(), now(), 0);
END $$;