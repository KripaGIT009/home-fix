package com.homefix.dispatch.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence for {@link PendingAcceptance}. */
public interface PendingAcceptanceRepository extends JpaRepository<PendingAcceptance, UUID> {

    /** Acceptances due for another attempt, oldest first. */
    List<PendingAcceptance> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(Instant now,
                                                                                    Pageable page);

    /**
     * Deletes the booking's acceptance with a single statement and reports how many rows went.
     *
     * <p>A bulk delete rather than {@code delete(entity)}: when two runners finish the same
     * acceptance at once, the database makes the second statement wait for the first and then
     * delete nothing, and the count of 0 is what tells that runner not to write a second event.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingAcceptance p where p.bookingId = :bookingId")
    int deleteByBookingIdReturningCount(@Param("bookingId") UUID bookingId);
}
