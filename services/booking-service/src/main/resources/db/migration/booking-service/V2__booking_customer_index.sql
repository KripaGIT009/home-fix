-- Customer service history (GET /bookings/history): one customer's bookings, newest first,
-- paged by offset. idx_booking_customer (customer_id) finds the rows but leaves Postgres to sort
-- every booking the customer has before it can skip to the page; with created_at in the key, in
-- the query's own direction, the page is read straight off the index.
--
-- A plain CREATE INDEX briefly blocks writes to booking.booking while it builds. That is fine at
-- the table's current size; CONCURRENTLY would need this migration to run outside Flyway's
-- transaction. IF NOT EXISTS keeps the migration safe on a database where the index was already
-- created by hand.
CREATE INDEX IF NOT EXISTS idx_booking_customer_created ON booking.booking (customer_id, created_at DESC);
