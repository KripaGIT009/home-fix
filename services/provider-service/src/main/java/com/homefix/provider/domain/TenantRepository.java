package com.homefix.provider.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Tenant}s (Requirement MT-1).
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** Every Tenant for the Platform_Admin list (Requirement MT-1.5), by name. */
    @Query("select t from Tenant t order by lower(t.name) asc, t.id asc")
    List<Tenant> findAllForAdmin();

    /** The applicant's latest agency application, whatever its status (email-auth Requirement 5.5). */
    Optional<Tenant> findFirstByApplicantUserIdOrderByCreatedAtDesc(UUID applicantUserId);

    /** Whether the user has an application pending or approved (at most one, Requirement 5.2). */
    boolean existsByApplicantUserIdAndStatusIn(UUID applicantUserId, Collection<TenantStatus> statuses);

    /**
     * Coverage pre-filter (Requirement MT-4.1, Property MT2): the Tenants with {@code status} that
     * serve {@code categoryId} and whose base lies inside the given box. The box is a superset of
     * every circle that could reach the booking, so the exact haversine distance against each
     * Tenant's own radius is decided in Java over this (small) result.
     */
    @Query("""
            select distinct t from Tenant t join t.categoryIds c
            where t.status = :status
              and c = :categoryId
              and t.baseLatitude between :minLat and :maxLat
              and t.baseLongitude between :minLon and :maxLon
            """)
    List<Tenant> findCoverageCandidates(@Param("status") TenantStatus status,
                                        @Param("categoryId") UUID categoryId,
                                        @Param("minLat") double minLat,
                                        @Param("maxLat") double maxLat,
                                        @Param("minLon") double minLon,
                                        @Param("maxLon") double maxLon);
}
