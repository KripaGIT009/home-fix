package com.homefix.admin.audit;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Records every Admin action in the immutable Audit_Log (Requirement 19.8).
 *
 * <p>The recorded row always carries the Admin's user ID, action type, affected entity type and
 * ID, and timestamp. The before/after field maps are serialised to JSON:
 * <ul>
 *   <li>{@link #recordCreate} — initial field values captured as {@code afterValues}.</li>
 *   <li>{@link #recordUpdate} — before and after values captured for the modified fields.</li>
 *   <li>{@link #recordDelete} — field values at time of deletion captured as {@code beforeValues}.</li>
 *   <li>{@link #recordApprove}/{@link #recordReject} — moderation transitions with before/after
 *       state.</li>
 * </ul>
 *
 * <p>A {@link Clock} is injected so the {@code loggedAt} timestamp is deterministic under test.
 */
@Service
public class AuditLogService {

    private final AuditLogStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditLogService(AuditLogStore store, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AuditLogEntry recordCreate(UUID actorId, String entityType, String entityId,
                                      Map<String, Object> initialValues) {
        return record(actorId, AdminAction.CREATE, entityType, entityId, null, initialValues);
    }

    public AuditLogEntry recordUpdate(UUID actorId, String entityType, String entityId,
                                      Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        return record(actorId, AdminAction.UPDATE, entityType, entityId, beforeValues, afterValues);
    }

    public AuditLogEntry recordDelete(UUID actorId, String entityType, String entityId,
                                      Map<String, Object> valuesAtDeletion) {
        return record(actorId, AdminAction.DELETE, entityType, entityId, valuesAtDeletion, null);
    }

    public AuditLogEntry recordApprove(UUID actorId, String entityType, String entityId,
                                       Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        return record(actorId, AdminAction.APPROVE, entityType, entityId, beforeValues, afterValues);
    }

    public AuditLogEntry recordReject(UUID actorId, String entityType, String entityId,
                                      Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        return record(actorId, AdminAction.REJECT, entityType, entityId, beforeValues, afterValues);
    }

    /**
     * Core append. Any supplied value maps are serialised to JSON; a null map serialises to a
     * null column (e.g. no {@code beforeValues} for a CREATE).
     */
    public AuditLogEntry record(UUID actorId, AdminAction action, String entityType, String entityId,
                                Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        AuditLogEntry entry = new AuditLogEntry(
                UUID.randomUUID(),
                actorId,
                action,
                entityType,
                entityId,
                toJson(beforeValues),
                toJson(afterValues),
                clock.instant());
        return store.append(entry);
    }

    private String toJson(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            // An audit record must never be silently dropped; surface the failure.
            throw new IllegalStateException("Failed to serialise audit values", ex);
        }
    }
}
