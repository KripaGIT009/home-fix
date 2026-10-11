-- Agency applications (email-auth spec, Requirement 5): a service company registers itself from the
-- Admin Portal and is usable only once a Platform_Admin approves it.
--
-- PENDING_APPROVAL and REJECTED join the Tenant statuses. Coverage already reads ACTIVE Tenants
-- only, so a pending or rejected application covers no booking (Property EA4). 'PENDING_APPROVAL'
-- is exactly 16 characters, so the column keeps its width.

ALTER TABLE provider.tenant DROP CONSTRAINT tenant_status_check;
ALTER TABLE provider.tenant
    ADD CONSTRAINT tenant_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING_APPROVAL', 'REJECTED'));

-- Who applied (null for a Tenant a Platform_Admin created), and why an application was rejected.
ALTER TABLE provider.tenant ADD COLUMN applicant_user_id uuid;
ALTER TABLE provider.tenant ADD COLUMN rejection_reason varchar(500);

-- A user has at most one pending or active application (Requirement 5.2), whatever races the
-- service loses.
CREATE UNIQUE INDEX uq_tenant_open_application ON provider.tenant (applicant_user_id)
    WHERE status IN ('PENDING_APPROVAL', 'ACTIVE') AND applicant_user_id IS NOT NULL;
