-- Converts outbox.outbox_event.payload from a large-object reference to inline text.
--
-- The entity used to map the payload with @Lob, which Hibernate renders on PostgreSQL as
-- `oid` -- a pointer into pg_largeobject rather than the JSON itself. The relay then read it
-- through the large-object API, whose stream is only valid inside the transaction that
-- opened it, so every poll cycle failed with "Unable to access lob stream". Rows piled up as
-- PENDING with no error recorded and no event was ever published.
--
-- The mapping is now text (OutboxEventEntity.payload), so a database created from scratch
-- gets the right type from ddl-auto. This script is only for a volume that already has the
-- oid column. It is idempotent: on an already-migrated database it does nothing.
--
--   docker exec -i homefix-core-postgres-1 psql -U homefix -d homefix \
--     -v ON_ERROR_STOP=1 < docker/migrate-outbox-payload.sql

DO $$
DECLARE
    current_type text;
    unlinked     bigint;
BEGIN
    SELECT data_type INTO current_type
      FROM information_schema.columns
     WHERE table_schema = 'outbox'
       AND table_name   = 'outbox_event'
       AND column_name  = 'payload';

    IF current_type IS NULL THEN
        RAISE NOTICE 'outbox.outbox_event does not exist yet; nothing to migrate.';
        RETURN;
    END IF;

    IF current_type <> 'oid' THEN
        RAISE NOTICE 'payload is already %, nothing to migrate.', current_type;
        RETURN;
    END IF;

    -- Keep the large-object ids so they can be unlinked once the column no longer
    -- references them; dropping the reference alone leaks them in pg_largeobject.
    CREATE TEMP TABLE _old_lobs ON COMMIT DROP AS
        SELECT payload AS loid FROM outbox.outbox_event;

    ALTER TABLE outbox.outbox_event
        ALTER COLUMN payload TYPE text
        USING convert_from(lo_get(payload), 'UTF8');

    SELECT count(*) INTO unlinked FROM (SELECT lo_unlink(loid) FROM _old_lobs) u;
    RAISE NOTICE 'Migrated payload to text and unlinked % large object(s).', unlinked;
END
$$;
