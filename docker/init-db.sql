-- Postgres init for the local HomeFix stack.
-- Hibernate ddl-auto=update creates TABLES but not SCHEMAS, and each service sets
-- hibernate.default_schema to its own name. Create every schema up front so the
-- services can start against a fresh database.
-- Shared infrastructure schema for the transactional outbox. Every producer writes its
-- outbox_event rows here and the Outbox Processor drains this one table, so the relay and
-- the producers cannot disagree about where events live.
CREATE SCHEMA IF NOT EXISTS outbox;

-- The outbox table itself is created here too, rather than left to whichever producer's
-- ddl-auto=update starts first, so that a fresh database has every column the Outbox Processor
-- needs before it starts, including under ddl-auto=validate. It mirrors OutboxEventEntity exactly
-- (the DDL Hibernate generates for it); keep the two in step. next_attempt_at holds the relay's
-- claim lease or next retry time (NULL = due now). An existing volume gets that column from
-- docker/migrate-outbox-next-attempt.sql instead, since this script only runs on a fresh volume.
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
    next_attempt_at timestamp(6) with time zone,
    last_error      varchar(2000),
    version         bigint                      NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox.outbox_event (status, created_at);

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS booking;
CREATE SCHEMA IF NOT EXISTS customer;
CREATE SCHEMA IF NOT EXISTS provider;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS pricing;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS dispatch;
CREATE SCHEMA IF NOT EXISTS location;
CREATE SCHEMA IF NOT EXISTS verification;
CREATE SCHEMA IF NOT EXISTS invoice;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS complaint;
CREATE SCHEMA IF NOT EXISTS chat;
CREATE SCHEMA IF NOT EXISTS rating;
CREATE SCHEMA IF NOT EXISTS promotion;
CREATE SCHEMA IF NOT EXISTS admin;
