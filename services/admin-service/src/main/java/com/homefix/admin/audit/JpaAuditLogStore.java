package com.homefix.admin.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link AuditLogStore} adapter used in production.
 */
@Component
public class JpaAuditLogStore implements AuditLogStore {

    private final JpaAuditLogRepository repository;

    public JpaAuditLogStore(JpaAuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuditLogEntry append(AuditLogEntry entry) {
        return repository.save(entry);
    }

    @Override
    public List<AuditLogEntry> findByEntity(String entityType, String entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByLoggedAtDesc(entityType, entityId);
    }

    @Override
    public List<AuditLogEntry> findByActor(UUID actorId) {
        return repository.findByActorIdOrderByLoggedAtDesc(actorId);
    }
}
