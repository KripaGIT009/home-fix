package com.homefix.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * The Candidate_Tenants of queued bookings (Requirement MT-4.2). Only the operations the fallback
 * and the Tenant endpoints use are exposed, so the in-memory fake in tests stays small.
 */
public interface BookingTenantCandidateRepository
        extends Repository<BookingTenantCandidate, BookingTenantCandidate.Key> {

    <S extends BookingTenantCandidate> S save(S candidate);

    /** Whether {@code tenantId} was a Candidate_Tenant of {@code bookingId}. */
    boolean existsByBookingIdAndTenantId(UUID bookingId, UUID tenantId);

    List<BookingTenantCandidate> findByBookingId(UUID bookingId);
}
