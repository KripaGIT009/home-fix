-- Adds outbox.outbox_event.next_attempt_at, the column the Outbox Processor uses to claim rows
-- and to schedule retries.
--
-- The relay used to select PENDING rows without locking them, so two relay instances (or an
-- overlapping poll) published the same event twice, and it retried a failing publish by sleeping
-- in-line for up to minutes per event. It now claims rows with SELECT ... FOR UPDATE SKIP LOCKED
-- and writes a claim lease into next_attempt_at, and a failed publish sets next_attempt_at to the
-- time of the next retry instead of sleeping. NULL means "due now", which is what every existing
-- row and every row written by a producer that has not been rebuilt yet gets, so no backfill is
-- needed. The attempt count reuses the existing retry_count column.
--
-- A fresh volume gets the column from docker/init-db.sql. This script is for a volume created
-- before that, and it must have run before an outbox-processor running with ddl-auto=validate
-- starts, or that start fails with "missing column [next_attempt_at]". (Compose currently runs
-- every service, the relay included, with ddl-auto=update, which adds the column too; this script
-- makes the outcome independent of that and of which service happens to start first.)
--
-- Idempotent and safe to run at any time, including while services are up: ADD COLUMN IF NOT
-- EXISTS is a no-op once the column exists, and if a service's ddl-auto=update adds it at the same
-- moment, whichever statement runs second either does nothing or fails harmlessly (Hibernate only
-- logs it). Run it after postgres is healthy and before the rebuilt outbox-processor starts:
--
--   docker compose -f docker-compose.core.yml up -d postgres
--   docker exec -i homefix-core-postgres-1 psql -U homefix -d homefix \
--     -v ON_ERROR_STOP=1 < docker/migrate-outbox-next-attempt.sql
--   docker compose -f docker-compose.core.yml up -d --build

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NULL THEN
        RAISE NOTICE 'outbox.outbox_event does not exist yet; nothing to migrate.';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1
                 FROM information_schema.columns
                WHERE table_schema = 'outbox'
                  AND table_name   = 'outbox_event'
                  AND column_name  = 'next_attempt_at') THEN
        RAISE NOTICE 'next_attempt_at already exists; nothing to migrate.';
        RETURN;
    END IF;

    -- Same type Hibernate generates for the entity's Instant field. Nullable on purpose: a
    -- NOT NULL column without a default cannot be added to a table that already holds rows.
    ALTER TABLE outbox.outbox_event
        ADD COLUMN IF NOT EXISTS next_attempt_at timestamp(6) with time zone;
    RAISE NOTICE 'Added outbox.outbox_event.next_attempt_at; existing PENDING rows are due immediately.';
END
$$;
