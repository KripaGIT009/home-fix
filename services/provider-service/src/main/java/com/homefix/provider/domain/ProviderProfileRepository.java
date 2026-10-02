package com.homefix.provider.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link ProviderProfile} aggregates.
 */
public interface ProviderProfileRepository extends JpaRepository<ProviderProfile, UUID> {

    Optional<ProviderProfile> findByUserId(UUID userId);

    /**
     * Dispatch pre-filter (Requirement 8.2): the profiles that <em>could</em> be eligible for a
     * booking, narrowed in SQL so the eligibility endpoint never loads the whole provider table.
     *
     * <p>Everything that is cheap to express in SQL is applied here: a base location on file and
     * inside the latitude/longitude bounding box around the customer, not under review, emergency
     * availability when the booking is an emergency, and at least one skill tag among
     * {@code skillTags}. The box is a superset of the search circle, so the exact haversine
     * distance, each provider's own service radius, the availability schedule and the scores are
     * evaluated in Java over this (small) result.
     *
     * <p>{@code skillTags} must be non-empty and already lower-cased: tags are compared
     * case-insensitively, and an empty {@code IN ()} list is invalid SQL on some databases. The
     * caller returns no candidates without querying when no tag is required.
     */
    @Query("""
            select distinct p from ProviderProfile p join p.skillTags tag
            where p.baseLatitude is not null and p.baseLongitude is not null
              and p.baseLatitude between :minLat and :maxLat
              and p.baseLongitude between :minLon and :maxLon
              and p.underReview = false
              and (:emergency = false or p.emergencyAvailable = true)
              and lower(tag) in :skillTags
            """)
    List<ProviderProfile> findDispatchCandidates(@Param("minLat") double minLat,
                                                 @Param("maxLat") double maxLat,
                                                 @Param("minLon") double minLon,
                                                 @Param("maxLon") double maxLon,
                                                 @Param("emergency") boolean emergency,
                                                 @Param("skillTags") Collection<String> skillTags);

    /**
     * The Admin provider list (Requirement 19.2): every provider's admin columns, newest first,
     * bounded by {@code page}. A projection — no aggregate or EAGER collection is loaded.
     */
    @Query("""
            select new com.homefix.provider.domain.ProviderAdminRow(p.id, p.displayName, p.aggregateRating)
            from ProviderProfile p
            order by p.createdAt desc, p.id asc
            """)
    List<ProviderAdminRow> findAdminRows(Pageable page);

    /**
     * The Admin provider list filtered by display name (Requirement 19.2). {@code pattern} is a
     * lower-cased {@code LIKE} pattern whose literal {@code %}, {@code _} and {@code \} are escaped
     * with {@code \}; the caller builds it so the search is a case-insensitive substring match.
     */
    @Query("""
            select new com.homefix.provider.domain.ProviderAdminRow(p.id, p.displayName, p.aggregateRating)
            from ProviderProfile p
            where lower(p.displayName) like :pattern escape '\\'
            order by p.createdAt desc, p.id asc
            """)
    List<ProviderAdminRow> searchAdminRows(@Param("pattern") String pattern, Pageable page);

    /** The admin columns of the given providers, in no particular order. {@code ids} must be non-empty. */
    @Query("""
            select new com.homefix.provider.domain.ProviderAdminRow(p.id, p.displayName, p.aggregateRating)
            from ProviderProfile p
            where p.id in :ids
            """)
    List<ProviderAdminRow> findAdminRowsByIds(@Param("ids") Collection<UUID> ids);

    /**
     * Every skill tag of the given providers in one statement, for the "primary skill" column
     * (Requirement 19.2). {@code ids} must be non-empty.
     */
    @Query("""
            select new com.homefix.provider.domain.ProviderSkillTag(p.id, tag)
            from ProviderProfile p join p.skillTags tag
            where p.id in :ids
            """)
    List<ProviderSkillTag> findSkillTags(@Param("ids") Collection<UUID> ids);
}
