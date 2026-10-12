-- Two sweepers look bookings up by a single status every minute: the stalled-search sweeper
-- (SEARCHING_PROVIDER, review 17.5 item 4) and the additional-quote approval timeout
-- (CUSTOMER_APPROVAL_PENDING, Requirement 9.9). The booking table only grows and has no status
-- index, so without this each pass would scan it. Only bookings in those two in-flight states are
-- indexed, which keeps the index small; both queries compare status with a literal, which implies
-- the predicate under any plan. When each booking entered the state is read from booking_audit
-- through idx_booking_audit_booking. Additive: no data changes.
CREATE INDEX idx_booking_sweep_status ON booking.booking (status, created_at)
    WHERE status IN ('SEARCHING_PROVIDER', 'CUSTOMER_APPROVAL_PENDING');
