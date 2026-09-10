package com.homefix.rating.audit;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AuditLogEntry} (Requirement 15.9).
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {
}
