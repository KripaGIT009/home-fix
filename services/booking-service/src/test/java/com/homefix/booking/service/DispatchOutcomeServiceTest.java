package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;

/**
 * Tests for the transitions the Dispatch Engine requests (Requirements 8.6, 8.9).
 *
 * <p>These cover the defect that left the core flow broken: the Dispatch Engine asked for
 * {@code SEARCHING_PROVIDER -> PROVIDER_ACCEPTED} directly, which the state machine forbids, against
 * an endpoint that did not exist. The service now walks the legal two-step path and assigns the
 * provider, and both entry points tolerate the retries the caller's resilience stack performs.
 *
 * <p>The state machine, transition service and audit trail are real; only the repository is stubbed.
 */
class DispatchOutcomeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T09:30:00Z"), ZoneOffset.UTC);

    private BookingRepository bookingRepository;
    private BookingAuditRepository auditRepository;
    private DispatchOutcomeService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        auditRepository = mock(BookingAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DispatchOutcomeService(bookingRepository,
                new BookingTransitionService(new BookingStateMachine(), auditRepository, CLOCK));
    }

    private Booking searchingBooking() {
        Booking booking = Booking.create("HFX-2026-0004821", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true, null, new BigDecimal("1250.00"));
        booking.applyStatus(BookingStatus.SEARCHING_PROVIDER);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        return booking;
    }

    // ----- Acceptance (Requirement 8.6) -----

    @Test
    void acceptance_walksThroughProviderAssignedAndAssignsTheProvider() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();

        Booking result = service.markProviderAccepted(booking.getId(), providerId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        // The provider is recorded on the aggregate; before this, providerId was never set and every
        // downstream provider event carried a null provider.
        assertThat(result.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void acceptance_isIdempotentForTheSameProvider() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();

        service.markProviderAccepted(booking.getId(), providerId);
        Booking second = service.markProviderAccepted(booking.getId(), providerId);

        assertThat(second.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(second.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void acceptance_rejectsADifferentProviderOnAnAlreadyAcceptedBooking() {
        Booking booking = searchingBooking();
        service.markProviderAccepted(booking.getId(), UUID.randomUUID());

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void acceptance_rejectsAMissingProviderId() {
        Booking booking = searchingBooking();

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), null))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void acceptance_rejectsATransitionThatIsNotLegalFromTheCurrentState() {
        Booking booking = Booking.create("HFX-2026-0004822", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("100.00"));
        // Still CREATED: the customer has not confirmed, so nothing may be dispatched yet.
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void acceptance_rejectsAnUnknownBooking() {
        UUID unknown = UUID.randomUUID();
        when(bookingRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markProviderAccepted(unknown, UUID.randomUUID()))
                .isInstanceOf(BookingException.class);
    }

    // ----- Exhausted search (Requirement 8.9) -----

    @Test
    void searchingFailed_marksTheBookingFailed() {
        Booking booking = searchingBooking();

        Booking result = service.markSearchingFailed(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void searchingFailed_isIdempotent() {
        Booking booking = searchingBooking();

        service.markSearchingFailed(booking.getId());
        Booking second = service.markSearchingFailed(booking.getId());

        assertThat(second.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void searchingFailed_rejectsAnUnknownBooking() {
        UUID unknown = UUID.randomUUID();
        when(bookingRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSearchingFailed(unknown))
                .isInstanceOf(BookingException.class);
    }
}
