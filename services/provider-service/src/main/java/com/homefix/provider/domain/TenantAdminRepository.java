package com.homefix.provider.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link TenantAdmin} memberships (Requirement MT-2). {@code findById(userId)} is
 * "which Tenant does this user administer?" — the lookup behind every Tenant Portal request
 * (Requirement MT-10.1).
 */
public interface TenantAdminRepository extends JpaRepository<TenantAdmin, UUID> {

    List<TenantAdmin> findByTenantIdOrderByUserIdAsc(UUID tenantId);

    long countByTenantId(UUID tenantId);

    /** Administrators per Tenant, for the Platform_Admin list (Requirement MT-1.5). */
    @Query("select new com.homefix.provider.domain.TenantCount(a.tenantId, count(a)) "
            + "from TenantAdmin a group by a.tenantId")
    List<TenantCount> countPerTenant();

    /**
     * Removes {@code userId}'s administration of {@code tenantId} only — never of another Tenant.
     *
     * @return 1 when removed, 0 when the user does not administer that Tenant
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TenantAdmin a where a.tenantId = :tenantId and a.userId = :userId")
    int deleteMembership(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
