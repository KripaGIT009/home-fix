package com.homefix.admin.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.homefix.admin.audit.AdminAction;
import com.homefix.admin.audit.AuditCursor;
import com.homefix.admin.audit.AuditLogEntry;
import com.homefix.admin.audit.AuditLogStore;

/**
 * In-memory {@link AuditLogStore} fake for unit tests. Append-only, mirroring the production
 * contract; read queries return most-recent-first.
 */
public class InMemoryAuditLogStore implements AuditLogStore {

    private final List<AuditLogEntry> entries = new ArrayList<>();

    @Override
    public AuditLogEntry append(AuditLogEntry entry) {
        entries.add(entry);
        return entry;
    }

    @Override
    public List<AuditLogEntry> findByEntity(String entityType, String entityId) {
        return entries.stream()
                .filter(e -> e.getEntityType().equals(entityType))
                .filter(e -> entityId == null ? e.getEntityId() == null : entityId.equals(e.getEntityId()))
                .sorted(Comparator.comparing(AuditLogEntry::getLoggedAt).reversed())
                .toList();
    }

    @Override
    public List<AuditLogEntry> findByActor(UUID actorId) {
        return entries.stream()
                .filter(e -> e.getActorId().equals(actorId))
                .sorted(Comparator.comparing(AuditLogEntry::getLoggedAt).reversed())
                .toList();
    }

    @Override
    public List<AuditLogEntry> findPage(AdminAction action, String entityType, AuditCursor after, int limit) {
        Comparator<AuditLogEntry> newestFirst = Comparator.comparing(AuditLogEntry::getLoggedAt)
                .thenComparing(AuditLogEntry::getId)
                .reversed();
        return entries.stream()
                .filter(e -> action == null || e.getActionType() == action)
                .filter(e -> entityType == null
                        || e.getEntityType().toLowerCase(Locale.ROOT).contains(entityType.toLowerCase(Locale.ROOT)))
                .filter(e -> after == null || e.getLoggedAt().isBefore(after.loggedAt())
                        || (e.getLoggedAt().equals(after.loggedAt()) && e.getId().compareTo(after.id()) < 0))
                .sorted(newestFirst)
                .limit(limit)
                .toList();
    }

    /** All appended entries in insertion order (test-only inspection). */
    public List<AuditLogEntry> all() {
        return List.copyOf(entries);
    }
}
