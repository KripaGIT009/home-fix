package com.homefix.provider.domain;

import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One user's administration of one {@link Tenant} (Requirement MT-2). The user id is the primary
 * key, so a user administers at most one Tenant (Requirement MT-2.3, Property MT8).
 *
 * <p>{@link Persistable} with an "always new until loaded" flag makes Spring Data
 * {@code persist} a new row instead of {@code merge}-ing it. With an assigned id a merge would
 * quietly <em>move</em> an administrator of another Tenant onto this one; a persist hits the
 * primary key instead and fails, which is the refusal the rule needs.
 */
@Entity
@Table(name = "tenant_admin")
public class TenantAdmin implements Persistable<UUID> {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Transient
    private boolean isNew = true;

    protected TenantAdmin() {
        // JPA
    }

    public TenantAdmin(UUID tenantId, UUID userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
