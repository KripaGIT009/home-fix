package com.homefix.admin.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository backing the {@link AuditLogStore}. Only derived read queries and the
 * inherited {@code save} are used; no update or delete is exposed to callers.
 */
public interface JpaAuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByEntityTypeAndEntityIdOrderByLoggedAtDesc(String entityType, String entityId);

    List<AuditLogEntry> findByActorIdOrderByLoggedAtDesc(UUID actorId);
}
