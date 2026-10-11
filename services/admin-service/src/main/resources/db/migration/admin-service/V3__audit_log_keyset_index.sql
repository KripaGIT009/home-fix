-- Supports the Admin Portal's Audit Logs view (Requirement 19.8): the whole log newest first,
-- keyset-paginated on (logged_at, id). Without it every page sorts the full table.

CREATE INDEX audit_log_logged_at_id_idx ON admin.audit_log (logged_at DESC, id DESC);
