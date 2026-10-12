-- Tenant fallback and assignment (multi-tenant spec: Requirements MT-4 to MT-9; design
-- "booking-service"). Additive: existing bookings keep their status and get NULL in both new
-- columns, which is exactly "never routed to a Tenant", so no backfill is needed (Requirement
-- MT-15.2).
--
-- 1. The new state AWAITING_ASSIGNMENT must be accepted wherever a booking status is stored: the
--    booking itself and both ends of an audit row (Requirement MT-9.4). The baseline's checks are
--    Hibernate-generated enum lists, so each is dropped and re-created with the full list.
--    from_state stays nullable (the creation entry has none): a CHECK on NULL passes.
ALTER TABLE booking.booking DROP CONSTRAINT IF EXISTS booking_status_check;
ALTER TABLE booking.booking ADD CONSTRAINT booking_status_check CHECK (status IN (
        'CREATED', 'SEARCHING_PROVIDER', 'AWAITING_ASSIGNMENT', 'SEARCHING_FAILED',
        'PROVIDER_ASSIGNED', 'PROVIDER_ACCEPTED', 'PROVIDER_ON_THE_WAY', 'PROVIDER_ARRIVED',
        'JOB_STARTED', 'JOB_PAUSED', 'ADDITIONAL_QUOTE_REQUIRED', 'CUSTOMER_APPROVAL_PENDING',
        'JOB_COMPLETED', 'CUSTOMER_CONFIRMED', 'PAYMENT_PENDING', 'PAYMENT_COMPLETED',
        'DISPUTED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE booking.booking_audit DROP CONSTRAINT IF EXISTS booking_audit_from_state_check;
ALTER TABLE booking.booking_audit ADD CONSTRAINT booking_audit_from_state_check CHECK (from_state IN (
        'CREATED', 'SEARCHING_PROVIDER', 'AWAITING_ASSIGNMENT', 'SEARCHING_FAILED',
        'PROVIDER_ASSIGNED', 'PROVIDER_ACCEPTED', 'PROVIDER_ON_THE_WAY', 'PROVIDER_ARRIVED',
        'JOB_STARTED', 'JOB_PAUSED', 'ADDITIONAL_QUOTE_REQUIRED', 'CUSTOMER_APPROVAL_PENDING',
        'JOB_COMPLETED', 'CUSTOMER_CONFIRMED', 'PAYMENT_PENDING', 'PAYMENT_COMPLETED',
        'DISPUTED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE booking.booking_audit DROP CONSTRAINT IF EXISTS booking_audit_to_state_check;
ALTER TABLE booking.booking_audit ADD CONSTRAINT booking_audit_to_state_check CHECK (to_state IN (
        'CREATED', 'SEARCHING_PROVIDER', 'AWAITING_ASSIGNMENT', 'SEARCHING_FAILED',
        'PROVIDER_ASSIGNED', 'PROVIDER_ACCEPTED', 'PROVIDER_ON_THE_WAY', 'PROVIDER_ARRIVED',
        'JOB_STARTED', 'JOB_PAUSED', 'ADDITIONAL_QUOTE_REQUIRED', 'CUSTOMER_APPROVAL_PENDING',
        'JOB_COMPLETED', 'CUSTOMER_CONFIRMED', 'PAYMENT_PENDING', 'PAYMENT_COMPLETED',
        'DISPUTED', 'REFUNDED', 'CANCELLED'));

-- 2. The Tenant serving the booking (Requirement MT-8.1) and when it first entered the queue
--    (Requirement MT-7.2). Tenant ids are provider-service's: not a foreign key across schemas.
ALTER TABLE booking.booking
    ADD COLUMN tenant_id uuid,
    ADD COLUMN queued_for_assignment_at timestamp(6) with time zone;

-- 3. Candidate_Tenants snapshotted at queue time (design D5).
CREATE TABLE booking.booking_tenant_candidate (
    booking_id uuid NOT NULL REFERENCES booking.booking (id),
    tenant_id uuid NOT NULL,
    PRIMARY KEY (booking_id, tenant_id)
);

-- A Tenant's queue looks candidates up by tenant.
CREATE INDEX idx_booking_tenant_candidate_tenant ON booking.booking_tenant_candidate (tenant_id);
-- A Tenant's bookings, newest first (Requirement MT-8.3); only bookings with a Tenant are indexed.
CREATE INDEX idx_booking_tenant_created ON booking.booking (tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;
-- The assignment deadline (Requirement MT-7.1): the sweeper looks up bookings still waiting
-- (AWAITING_ASSIGNMENT) or still unconfirmed (PROVIDER_ASSIGNED) by first queue time, and the queue
-- reads AWAITING_ASSIGNMENT in queue order. Only bookings that went through the fallback have a
-- queue time, so the index stays small; the predicate is IS NOT NULL rather than a status list
-- because "queued_for_assignment_at < $1" implies it even under a generic plan with bound
-- parameters, which a status IN-list predicate would not.
CREATE INDEX idx_booking_assignment_deadline ON booking.booking (status, queued_for_assignment_at)
    WHERE queued_for_assignment_at IS NOT NULL;
