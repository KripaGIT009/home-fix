package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.BookingCancelledEvent;
import com.homefix.booking.event.ProviderAssignedEvent;
import com.homefix.booking.support.Bookings;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Verifies the transition applier records exactly one audit entry per valid transition
 * (Property 9) and rejects invalid transitions with no state change and no audit row
 * (Requirement 9.2, Property 8), and that the state-driven events (BookingCancelled,
 * ProviderAssigned) are written exactly once per transition into their state and never for a
 * rejected one (Requirement 22.1).
 */
@ExtendWith(MockitoExtension.class)
class BookingTransitionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BookingAuditRepository auditRepository;
    @Mock
    private OutboxEventPublisher outboxPublisher;

    private BookingTransitionService service;

    @BeforeEach
    void setUp() {
        service = new BookingTransitionService(new BookingStateMachine(), auditRepository,
                new BookingLifecycleEventPublisher(outboxPublisher, CLOCK), CLOCK);
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

    // ----- State-driven outbox events (Requirement 22.1, 22.2) -----

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class,
            names = {"SEARCHING_PROVIDER", "PROVIDER_ASSIGNED", "PROVIDER_ACCEPTED", "PROVIDER_ON_THE_WAY"})
    void everyCancellationWritesExactlyOneBookingCancelled(BookingStatus source) {
        Booking booking = Bookings.inState(source);
        Actor actor = Actor.user(UUID.randomUUID(), "ADMIN");

        service.transition(booking, BookingStatus.CANCELLED, actor, "duplicate booking");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(1)).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE),
                eq(booking.getId()), eq(BookingCancelledEvent.EVENT_TYPE), payload.capture());
        BookingCancelledEvent event = (BookingCancelledEvent) payload.getValue();
        assertThat(event.bookingId()).isEqualTo(booking.getId());
        assertThat(event.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(event.previousStatus()).isEqualTo(source);
        assertThat(event.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(event.cancelledBy()).isEqualTo(actor.id());
        assertThat(event.cancelledByRole()).isEqualTo("ADMIN");
        assertThat(event.reason()).isEqualTo("duplicate booking");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        // Nothing else is published for a cancellation.
        verify(outboxPublisher, times(1)).publish(any(), any(), any(), any());
    }

    @Test
    void searchingFailedWritesBookingCancelledWithItsOwnStatus() {
        Booking booking = Bookings.inState(BookingStatus.SEARCHING_PROVIDER);

        service.transition(booking, BookingStatus.SEARCHING_FAILED, Actor.system(), "no provider");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(1)).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE),
                eq(booking.getId()), eq(BookingCancelledEvent.EVENT_TYPE), payload.capture());
        BookingCancelledEvent event = (BookingCancelledEvent) payload.getValue();
        assertThat(event.status()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(event.previousStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(event.cancelledBy()).isNull();
        assertThat(event.cancelledByRole()).isEqualTo("booking-service");
        assertThat(event.providerId()).isNull();
    }

    @Test
    void assignmentWritesExactlyOneProviderAssigned() {
        Booking booking = Bookings.inState(BookingStatus.SEARCHING_PROVIDER);
        UUID providerId = UUID.randomUUID();
        booking.setProviderId(providerId);

        service.transition(booking, BookingStatus.PROVIDER_ASSIGNED, Actor.system(), "assigned");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(1)).publish(eq(ProviderAssignedEvent.AGGREGATE_TYPE),
                eq(booking.getId()), eq(ProviderAssignedEvent.EVENT_TYPE), payload.capture());
        ProviderAssignedEvent event = (ProviderAssignedEvent) payload.getValue();
        assertThat(event.bookingId()).isEqualTo(booking.getId());
        assertThat(event.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(event.providerId()).isEqualTo(providerId);
        assertThat(event.reference()).isEqualTo(booking.getReference());
        verify(outboxPublisher, times(1)).publish(any(), any(), any(), any());
    }

    @Test
    void rejectedCancellationPublishesNothing() {
        Booking booking = Bookings.inState(BookingStatus.JOB_STARTED);

        assertThatThrownBy(() -> service.transition(booking, BookingStatus.CANCELLED,
                Actor.user(booking.getCustomerId(), "CUSTOMER"), "too late"))
                .isInstanceOf(InvalidTransitionException.class);

        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void transitionsWithoutAStateDrivenEventPublishNothing() {
        Booking booking = Bookings.inState(BookingStatus.CREATED);
        Actor actor = Actor.user(booking.getCustomerId(), "CUSTOMER");

        service.transition(booking, BookingStatus.SEARCHING_PROVIDER, actor, null);
        booking.applyStatus(BookingStatus.PROVIDER_ASSIGNED);
        service.transition(booking, BookingStatus.PROVIDER_ACCEPTED, actor, null);

        verifyNoInteractions(outboxPublisher);
    }

    // ----- Passing through an intermediate state -----

    @Test
    void passingThroughProviderAssignedIsAuditedButPublishesNothing() {
        Booking booking = Bookings.inState(BookingStatus.SEARCHING_PROVIDER);
        booking.setProviderId(UUID.randomUUID());

        service.transitionPassingThrough(booking, BookingStatus.PROVIDER_ASSIGNED, Actor.system(), "assigned");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        ArgumentCaptor<BookingAudit> captor = ArgumentCaptor.forClass(BookingAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getFromState()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(captor.getValue().getToState()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void passingThroughIsStillValidatedByTheStateMachine() {
        Booking booking = Bookings.inState(BookingStatus.CREATED);

        assertThatThrownBy(() -> service.transitionPassingThrough(booking, BookingStatus.PROVIDER_ASSIGNED,
                Actor.system(), "skip"))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CREATED);
        verify(auditRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CANCELLED", "SEARCHING_FAILED"})
    void aTerminalStateCannotBePassedThroughSoBookingCancelledCannotBeSuppressed(BookingStatus terminal) {
        Booking booking = Bookings.inState(BookingStatus.SEARCHING_PROVIDER);

        assertThatThrownBy(() -> service.transitionPassingThrough(booking, terminal, Actor.system(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        verifyNoInteractions(outboxPublisher);
    }
}
