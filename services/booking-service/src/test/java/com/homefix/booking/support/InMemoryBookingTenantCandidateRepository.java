package com.homefix.booking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.booking.domain.BookingTenantCandidate;
import com.homefix.booking.domain.BookingTenantCandidateRepository;

/** In-memory {@link BookingTenantCandidateRepository} for service tests without a database. */
public class InMemoryBookingTenantCandidateRepository implements BookingTenantCandidateRepository {

    private final List<BookingTenantCandidate> rows = new ArrayList<>();

    @Override
    public <S extends BookingTenantCandidate> S save(S candidate) {
        if (!existsByBookingIdAndTenantId(candidate.getBookingId(), candidate.getTenantId())) {
            rows.add(candidate);
        }
        return candidate;
    }

    @Override
    public boolean existsByBookingIdAndTenantId(UUID bookingId, UUID tenantId) {
        return rows.stream().anyMatch(r -> r.getBookingId().equals(bookingId) && r.getTenantId().equals(tenantId));
    }

    @Override
    public List<BookingTenantCandidate> findByBookingId(UUID bookingId) {
        return rows.stream().filter(r -> r.getBookingId().equals(bookingId)).toList();
    }
}
