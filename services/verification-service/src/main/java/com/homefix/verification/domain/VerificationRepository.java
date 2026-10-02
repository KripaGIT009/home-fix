package com.homefix.verification.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Verification} aggregates. The verification record is keyed by its own
 * id but looked up by {@code providerId} in the domain flows (Requirement 5).
 *
 * <p>Both of the aggregate's collections are lazy. {@link #findByProviderId} loads the root row
 * only — enough for the status checks behind the dispatch eligibility gate (Requirement 5.10).
 * {@link #findWithDocumentsByProviderId} additionally fetch-joins the documents; the audit trail is
 * deliberately <em>not</em> joined as well, because joining two list collections in one query is
 * a cartesian product (and Hibernate refuses it outright as a multiple-bag fetch).
 */
public interface VerificationRepository extends JpaRepository<Verification, UUID> {

    Optional<Verification> findByProviderId(UUID providerId);

    /** The verification with its documents fetched in the same query; the audit trail stays lazy. */
    @EntityGraph(attributePaths = "documents")
    Optional<Verification> findWithDocumentsByProviderId(UUID providerId);

    boolean existsByProviderId(UUID providerId);

    /**
     * The ids among {@code providerIds} whose verification is in {@code status}: a projection of
     * the root rows only, one statement however many ids, no aggregate or collection loaded. Backs
     * the dispatch eligibility batch lookup (Requirements 5.10, 8.2). {@code providerIds} must be
     * non-empty — an empty {@code IN ()} list is invalid SQL on some databases.
     */
    @Query("select v.providerId from Verification v "
            + "where v.providerId in :providerIds and v.status = :status")
    List<UUID> findProviderIdsByStatus(@Param("providerIds") Collection<UUID> providerIds,
                                       @Param("status") VerificationStatus status);

    /**
     * The verifications in {@code status} with their document count and latest upload, ordered by
     * submission time oldest first (latest upload, else the record's last change) — the Admin
     * review queue (Requirement 19.3). One grouped statement over the root rows and documents; no
     * aggregate is loaded. {@code page} bounds the result (the queue is capped server-side).
     */
    @Query("select new com.homefix.verification.domain.VerificationQueueRow("
            + "v.providerId, count(d), max(d.uploadedAt), v.updatedAt) "
            + "from Verification v left join v.documents d "
            + "where v.status = :status "
            + "group by v.providerId, v.updatedAt "
            + "order by coalesce(max(d.uploadedAt), v.updatedAt) asc, v.providerId asc")
    List<VerificationQueueRow> findQueue(@Param("status") VerificationStatus status, Pageable page);

    /**
     * The current status of each of {@code providerIds} that has a verification record, as a
     * projection of the root rows in one statement. Ids with no record are absent. Backs the
     * Provider Service's Admin provider list (Requirement 19.2). {@code providerIds} must be
     * non-empty, as for {@link #findProviderIdsByStatus}.
     */
    @Query("select new com.homefix.verification.domain.VerificationStatusView(v.providerId, v.status) "
            + "from Verification v where v.providerId in :providerIds")
    List<VerificationStatusView> findStatusesByProviderIds(
            @Param("providerIds") Collection<UUID> providerIds);
}
