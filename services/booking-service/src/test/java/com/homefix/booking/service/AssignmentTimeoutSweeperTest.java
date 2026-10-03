package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.BookingCancelledEvent;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.booking.support.MutableClock;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * The assignment timeout (Requirement MT-7, Property MT6): the boundary, the customer notification
 * through the state-driven {@code BookingCancelled}, that a decline does not extend the deadline,
 * and that an assignment the Provider never answers fails too, while automatic dispatch is never
 * touched. Two sweepers racing over real transactions are {@code TenantAssignmentConcurrencyTest}'s.
 */
class AssignmentTimeoutSweeperTest {

    private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

    private InMemoryBookingRepository repository;
    private OutboxEventPublisher outbox;
    private MutableClock clock;
    private AssignmentTimeoutSweeper sweeper;
    private final List<BookingAudit> audits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        outbox = mock(OutboxEventPublisher.class);
        clock = new MutableClock(T0);
        audits.clear();
        BookingAuditRepository auditRepository = mock(BookingAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> {
            audits.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        BookingProperties properties = new BookingProperties();
        properties.setTenantAssignmentTimeout(Duration.ofMinutes(60));
        sweeper = new AssignmentTimeoutSweeper(repository,
                new BookingTransitionService(new BookingStateMachine(), auditRepository,
                        new BookingLifecycleEventPublisher(outbox, clock), clock),
                TransactionOperations.withoutTransaction(), properties, clock);
    }

    private Booking queuedAt(Instant queued, BookingStatus status) {
        Booking booking = Bookings.placed(UUID.randomUUID(), UUID.randomUUID(), "HFX-" + UUID.randomUUID(), T0, null);
        booking.applyStatus(status);
        booking.setQueuedForAssignmentAt(queued);
        booking.setTenantId(UUID.randomUUID());
        return repository.save(booking);
    }

    @Test
    void aBookingWaitingLongerThanTheTimeoutFailsAndTheCustomerIsTold() {
        Booking overdue = queuedAt(T0, BookingStatus.AWAITING_ASSIGNMENT);
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(overdue.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE), eq(overdue.getId()),
                eq(BookingCancelledEvent.EVENT_TYPE), payload.capture());
        BookingCancelledEvent event = (BookingCancelledEvent) payload.getValue();
        assertThat(event.previousStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(event.status()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void aBookingExactlyAtTheTimeoutIsLeftForTheNextPass() {
        Booking atLimit = queuedAt(T0, BookingStatus.AWAITING_ASSIGNMENT);
        clock.advance(Duration.ofMinutes(60));

        assertThat(sweeper.sweep()).isZero();
        assertThat(atLimit.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);

        clock.advance(Duration.ofSeconds(1));
        assertThat(sweeper.sweep()).isEqualTo(1);
    }

    @Test
    void aDeclineDoesNotExtendTheDeadline() {
        // Queued at T0, assigned and declined at T0+50m: the queue time is still T0.
        Booking declined = queuedAt(T0, BookingStatus.AWAITING_ASSIGNMENT);
        clock.advance(Duration.ofMinutes(61));

        sweeper.sweep();

        assertThat(declined.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void anAssignmentTheProviderNeverAnsweredFailsThroughTheQueue() {
        Booking assigned = queuedAt(T0, BookingStatus.PROVIDER_ASSIGNED);
        UUID provider = UUID.randomUUID();
        assigned.setProviderId(provider);
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(assigned.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        // Two audited steps over existing edges, both by the system (Requirement MT-9.2).
        assertThat(audits).extracting(BookingAudit::getFromState, BookingAudit::getToState).containsExactly(
                tuple(BookingStatus.PROVIDER_ASSIGNED, BookingStatus.AWAITING_ASSIGNMENT),
                tuple(BookingStatus.AWAITING_ASSIGNMENT, BookingStatus.SEARCHING_FAILED));
        assertThat(audits).allSatisfy(a -> assertThat(a.getActorId()).isNull());
        assertThat(audits.get(0).getReason()).contains("did not confirm before the assignment deadline");
        // One BookingCancelled: the customer gets the no-professional notice, and the event names the
        // provider, who is told the job is off. The passed-through queue step announces nothing.
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(1)).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE), eq(assigned.getId()),
                eq(BookingCancelledEvent.EVENT_TYPE), payload.capture());
        BookingCancelledEvent event = (BookingCancelledEvent) payload.getValue();
        assertThat(event.status()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(event.providerId()).isEqualTo(provider);
        verify(outbox, times(1)).publish(any(), any(), any(), any());
        // SEARCHING_FAILED is not an active job: it leaves the provider's dashboard.
        assertThat(repository.findByProviderIdAndStatusInOrderByScheduledAtAsc(provider,
                EnumSet.of(BookingStatus.PROVIDER_ASSIGNED, BookingStatus.PROVIDER_ACCEPTED))).isEmpty();
    }

    @Test
    void anUnansweredAssignmentIsLeftAloneUntilTheDeadlinePasses() {
        Booking assigned = queuedAt(T0, BookingStatus.PROVIDER_ASSIGNED);
        clock.advance(Duration.ofMinutes(60));

        assertThat(sweeper.sweep()).isZero();
        assertThat(assigned.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);

        clock.advance(Duration.ofSeconds(1));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(assigned.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void anAutomaticallyDispatchedBookingIsNeverSwept() {
        // The Dispatch Engine's acceptance passes through PROVIDER_ASSIGNED without a queue time.
        Booking dispatched = queuedAt(null, BookingStatus.PROVIDER_ASSIGNED);
        Booking accepted = queuedAt(T0, BookingStatus.PROVIDER_ACCEPTED);
        clock.advance(Duration.ofHours(5));

        assertThat(sweeper.sweep()).isZero();
        assertThat(dispatched.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        assertThat(accepted.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void aSecondPassChangesNothing() {
        queuedAt(T0, BookingStatus.AWAITING_ASSIGNMENT);
        queuedAt(T0.plus(Duration.ofMinutes(30)), BookingStatus.AWAITING_ASSIGNMENT);
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).isZero();
        verify(outbox, times(1)).publish(any(), any(), any(), any());
    }
}
