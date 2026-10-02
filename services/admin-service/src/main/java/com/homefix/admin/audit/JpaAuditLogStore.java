package com.homefix.admin.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link AuditLogStore} adapter used in production.
 */
@Component
public class JpaAuditLogStore implements AuditLogStore {

    private final JpaAuditLogRepository repository;
    private final EntityManager entityManager;

    public JpaAuditLogStore(JpaAuditLogRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
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

    /**
     * Keyset page over {@code (logged_at, id)}. Built with the Criteria API so an absent filter is
     * left out of the SQL altogether, rather than bound as a typed-null parameter in an
     * {@code (:x is null or ...)} clause, which PostgreSQL cannot always type and which defeats
     * index use.
     */
    @Override
    public List<AuditLogEntry> findPage(AdminAction action, String entityType, AuditCursor after, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditLogEntry> query = cb.createQuery(AuditLogEntry.class);
        Root<AuditLogEntry> entry = query.from(AuditLogEntry.class);
        Path<Instant> loggedAt = entry.get("loggedAt");
        Path<UUID> id = entry.get("id");

        List<Predicate> where = new ArrayList<>();
        if (action != null) {
            where.add(cb.equal(entry.get("actionType"), action));
        }
        if (entityType != null) {
            where.add(cb.like(cb.lower(entry.get("entityType")),
                    "%" + escapeLike(entityType.toLowerCase(Locale.ROOT)) + "%", '\\'));
        }
        if (after != null) {
            where.add(cb.or(
                    cb.lessThan(loggedAt, after.loggedAt()),
                    cb.and(cb.equal(loggedAt, after.loggedAt()), cb.lessThan(id, after.id()))));
        }
        query.select(entry)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.desc(loggedAt), cb.desc(id));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    /** Escapes LIKE wildcards so a filter of "user_" matches the text, not any character. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
