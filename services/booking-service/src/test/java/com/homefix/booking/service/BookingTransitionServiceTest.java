package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.Bookings;

/**
 * Verifies the transition applier records exactly one audit entry per valid transition
 * (Property 9) and rejects invalid transitions with no state change and no audit row
 * (Requirement 9.2, Property 8).
 */
@ExtendWith(MockitoExtension.class)
class BookingTransitionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BookingAuditRepository auditRepository;

    private BookingTransitionService service;

    @BeforeEach
    void setUp() {
        service = new BookingTransitionService(new BookingStateMachine(), auditRepository, CLOCK);
    }

    @Test
    void validTransitionUpdatesStatusAndWritesExactlyOneAudit() {
        Booking booking = Bookings.inState(BookingStatus.CREATED);
        Actor actor = Actor.user(UUID.randomUUID(), "CUSTOMER");

        service.transition(booking, BookingStatus.SEARCHING_PROVIDER, actor, "confirmed");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);

        ArgumentCaptor<BookingAudit> captor = ArgumentCaptor.forClass(BookingAudit.class);
        verify(auditRepository).save(captor.capture());
        BookingAudit audit = captor.getValue();
        assertThat(audit.getBookingId()).isEqualTo(booking.getId());
        assertThat(audit.getFromState()).isEqualTo(BookingStatus.CREATED);
        assertThat(audit.getToState()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(audit.getActorId()).isEqualTo(actor.id());
        assertThat(audit.getActorRole()).isEqualTo("CUSTOMER");
        assertThat(audit.getTransitionedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(audit.getReason()).isEqualTo("confirmed");
    }

    @Test
    void invalidTransitionThrowsAndWritesNoAudit() {
        Booking booking = Bookings.inState(BookingStatus.CREATED);
        Actor actor = Actor.user(UUID.randomUUID(), "CUSTOMER");

        assertThatThrownBy(() -> service.transition(booking, BookingStatus.JOB_COMPLETED, actor, "nope"))
                .isInstanceOf(InvalidTransitionException.class);

        // No state change and no audit row (Property 8).
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CREATED);
        verify(auditRepository, never()).save(any());
    }

    @Test
    void creationAuditOpensChainWithNullFromState() {
        Booking booking = Bookings.inState(BookingStatus.CREATED);
        Actor actor = Actor.user(booking.getCustomerId(), "CUSTOMER");

        service.recordCreation(booking, actor, "Booking created");

        ArgumentCaptor<BookingAudit> captor = ArgumentCaptor.forClass(BookingAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getFromState()).isNull();
        assertThat(captor.getValue().getToState()).isEqualTo(BookingStatus.CREATED);
    }

    @Test
    void sequenceOfTransitionsFormsAContiguousChain() {
        // Simulate the repository accumulating audit rows.
        List<BookingAudit> saved = new ArrayList<>();
        when(auditRepository.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        Booking booking = Bookings.inState(BookingStatus.CREATED);
        Actor customer = Actor.user(booking.getCustomerId(), "CUSTOMER");

        service.recordCreation(booking, customer, "created");
        service.transition(booking, BookingStatus.SEARCHING_PROVIDER, customer, null);
        service.transition(booking, BookingStatus.PROVIDER_ASSIGNED, Actor.system(), null);
        service.transition(booking, BookingStatus.PROVIDER_ACCEPTED, Actor.system(), null);

        // Chain: null->CREATED, CREATED->SEARCHING_PROVIDER, SEARCHING_PROVIDER->PROVIDER_ASSIGNED,
        // PROVIDER_ASSIGNED->PROVIDER_ACCEPTED. Each to_state equals the next from_state.
        assertThat(saved).hasSize(4);
        assertThat(saved.get(0).getFromState()).isNull();
        for (int i = 1; i < saved.size(); i++) {
            assertThat(saved.get(i).getFromState())
                    .as("contiguity at %d", i)
                    .isEqualTo(saved.get(i - 1).getToState());
        }
        assertThat(saved.get(saved.size() - 1).getToState()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
    }
}
