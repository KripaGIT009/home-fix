package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.homefix.booking.domain.BookingRepository;

/**
 * Verifies the booking reference format (Requirement 7.1) and uniqueness handling.
 */
class BookingReferenceGeneratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void generatesReferenceWithExpectedFormat() {
        BookingRepository repo = mock(BookingRepository.class);
        when(repo.existsByReference(any())).thenReturn(false);
        BookingReferenceGenerator gen = new BookingReferenceGenerator(repo, CLOCK);

        String ref = gen.generate();

        assertThat(ref).matches("HFX-20240601-[A-Z0-9]{6}");
    }

    @Test
    void retriesOnCollisionThenReturnsUniqueReference() {
        BookingRepository repo = mock(BookingRepository.class);
        // First candidate collides, second is free.
        when(repo.existsByReference(any())).thenReturn(true, false);
        BookingReferenceGenerator gen = new BookingReferenceGenerator(repo, CLOCK);

        String ref = gen.generate();
        assertThat(ref).startsWith("HFX-20240601-");
    }

    @Test
    void generatesDistinctReferencesAcrossManyCalls() {
        BookingRepository repo = mock(BookingRepository.class);
        when(repo.existsByReference(any())).thenReturn(false);
        BookingReferenceGenerator gen = new BookingReferenceGenerator(repo, CLOCK);

        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            refs.add(gen.generate());
        }
        // With a 31^6 space, 500 references are practically always distinct.
        assertThat(refs).hasSize(500);
    }
}
