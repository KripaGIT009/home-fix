package com.homefix.admin.audit;

import java.util.List;
import java.util.UUID;

/**
 * Append-only persistence port for the Audit_Log (Requirement 19.8, 26.10).
 *
 * <p>Deliberately exposes only {@code append} and read operations — there is no update or delete,
 * reflecting the tamper-evident, append-only contract of the underlying store. Production wires a
 * JPA-backed adapter; tests use an in-memory fake.
 */
public interface AuditLogStore {

    /** Persists a new audit entry. The entry is never subsequently mutated. */
    AuditLogEntry append(AuditLogEntry entry);

    /** Returns all audit entries for a given affected entity, most recent first. */
    List<AuditLogEntry> findByEntity(String entityType, String entityId);

    /** Returns all audit entries produced by a given Admin actor, most recent first. */
    List<AuditLogEntry> findByActor(UUID actorId);

    /**
     * Returns one page of the whole log in {@code loggedAt DESC, id DESC} order (the Admin Portal's
     * Audit Logs view, Requirement 19.8).
     *
     * @param action     only entries of this action, or {@code null} for all
     * @param entityType only entries whose entity type contains this text, ignoring case, or
     *                   {@code null} for all
     * @param after      only entries strictly after this position, or {@code null} from the start
     * @param limit      the maximum number of entries to return
     */
    List<AuditLogEntry> findPage(AdminAction action, String entityType, AuditCursor after, int limit);
}
