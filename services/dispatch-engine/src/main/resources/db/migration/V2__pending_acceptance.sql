-- Provider acceptances recorded but not yet fully applied (Requirement 8.6). See
-- com.homefix.dispatch.port.AcceptanceLedger: a row is written before the Booking Service is asked
-- for PROVIDER_ACCEPTED and deleted in the same transaction as the ProviderAccepted outbox row, so a
-- failure between the two can be retried by the AcceptanceReconciler instead of losing the event.
--
-- The first versioned migration of this schema. V1 is the baseline (spring.flyway.baseline-version),
-- which has no file because the Dispatch Engine had no tables of its own before this one: an existing
-- database is baselined at V1 and gets this migration, a new one simply runs it.
--
-- Mirrors com.homefix.dispatch.domain.PendingAcceptance exactly (ddl-auto=validate checks it).

CREATE TABLE IF NOT EXISTS dispatch.pending_acceptance (
    booking_id         uuid                        NOT NULL,
    customer_id        uuid                        NOT NULL,
    provider_id        uuid                        NOT NULL,
    booking_created_at timestamp(6) with time zone,
    accepted_at        timestamp(6) with time zone NOT NULL,
    -- true once the Booking Service has confirmed the transition; a retry then only announces.
    booking_accepted   boolean                     NOT NULL DEFAULT false,
    attempts           integer                     NOT NULL DEFAULT 0,
    -- The dispatch thread's lease on a new row, then the next retry time.
    next_attempt_at    timestamp(6) with time zone NOT NULL,
    last_error         varchar(2000),
    PRIMARY KEY (booking_id)
);

CREATE INDEX IF NOT EXISTS idx_pending_acceptance_next_attempt
    ON dispatch.pending_acceptance (next_attempt_at);
