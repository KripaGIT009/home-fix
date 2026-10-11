-- Shared outbox infrastructure: the transactional outbox and the idempotent-consumer log.
--
-- Both tables live in the shared "outbox" schema rather than any one service's, because every
-- producer writes outbox_event rows into the same table the Outbox Processor drains, and every
-- consumer records processed_event rows there. No single service owns them, so this migration
-- ships inside homefix-shared-outbox and every service that depends on the library applies it.
--
-- It is a repeatable migration and every statement is idempotent: each service applies it once
-- (and again whenever this file changes), and the result is the same whichever service starts
-- first. Services keep their Flyway history in their own schemas, so Flyway's own lock does not
-- serialise them; the advisory lock below does, so concurrent cold starts cannot race on
-- CREATE TABLE IF NOT EXISTS.
--
-- Mirrors OutboxEventEntity and ProcessedEventEntity exactly (ddl-auto=validate checks them).
-- docker/init-db.sql creates the same outbox_event table for the local stack; keep them in step.

SELECT pg_advisory_xact_lock(hashtext('homefix-shared-outbox-schema'));

CREATE SCHEMA IF NOT EXISTS outbox;

CREATE TABLE IF NOT EXISTS outbox.outbox_event (
    id              uuid                        NOT NULL,
    aggregate_type  varchar(100)                NOT NULL,
    aggregate_id    uuid                        NOT NULL,
    event_type      varchar(150)                NOT NULL,
    payload         text                        NOT NULL,
    status          varchar(20)                 NOT NULL
                    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count     integer                     NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    published_at    timestamp(6) with time zone,
    -- The relay's claim lease or next retry time; NULL means due now.
    next_attempt_at timestamp(6) with time zone,
    last_error      varchar(2000),
    version         bigint                      NOT NULL,
    PRIMARY KEY (id)
);
-- Databases created before next_attempt_at existed.
ALTER TABLE outbox.outbox_event ADD COLUMN IF NOT EXISTS next_attempt_at timestamp(6) with time zone;
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox.outbox_event (status, created_at);

-- One row per (consumer group, event) a consumer has handled; the primary key is what makes a
-- redelivered event a no-op.
CREATE TABLE IF NOT EXISTS outbox.processed_event (
    consumer_group varchar(150)                NOT NULL,
    event_id       uuid                        NOT NULL,
    processed_at   timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
