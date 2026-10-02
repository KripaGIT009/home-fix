-- A booking pays its provider once. The Payment Service delivers the wallet credit at least once
-- (it re-sends a credit whose confirmation it never durably recorded), so the Provider Service
-- must apply a repeated JOB_CREDIT for the same booking only once. ProviderService checks first;
-- this index is the guarantee when two deliveries race.
CREATE UNIQUE INDEX uq_provider_earning_job_credit_booking
    ON provider.provider_earning (booking_id)
    WHERE type = 'JOB_CREDIT' AND booking_id IS NOT NULL;
