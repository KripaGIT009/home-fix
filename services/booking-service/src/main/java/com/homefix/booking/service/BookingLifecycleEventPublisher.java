package com.homefix.booking.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.BookingCancelledEvent;
import com.homefix.booking.event.ProviderAssignedEvent;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Publishes the booking events that follow from <em>entering a state</em>, whatever caused the
 * transition (Requirement 22.1, 22.2):
 * <pre>
 *   CANCELLED          -> BookingCancelled
 *   SEARCHING_FAILED   -> BookingCancelled  (status = SEARCHING_FAILED)
 *   PROVIDER_ASSIGNED  -> ProviderAssigned
 * </pre>
 *
 * <p>{@link BookingTransitionService} calls this for every applied transition, so the event cannot
 * be skipped by a new or alternative path into these states: a customer, provider or admin
 * cancellation, the generic guarded transition, or a Dispatch Engine callback all produce exactly
 * one outbox row. The exception is a state the caller passes through within one transaction
 * ({@link BookingTransitionService#transitionPassingThrough}), which publishes nothing. Events that carry command-specific data (BookingCreated, the job-execution
 * milestones) stay with the command that produces them.
 *
 * <p>The row is written through the shared {@link OutboxEventPublisher}, whose
 * {@code Propagation.MANDATORY} joins the caller's transaction, so the event commits or rolls back
 * with the status change and its audit row.
 */
@Component
public class BookingLifecycleEventPublisher {

    private final OutboxEventPublisher outboxPublisher;
    private final Clock clock;

    public BookingLifecycleEventPublisher(OutboxEventPublisher outboxPublisher, Clock clock) {
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
    }

    /**
     * Writes the outbox row, if any, for {@code booking} having just moved {@code from -> to}.
     * Transitions into any other state publish nothing here.
     */
    public void onTransition(Booking booking, BookingStatus from, BookingStatus to,
                             Actor actor, String reason) {
        onTransition(booking, from, to, actor, reason, null);
    }

    /**
     * As {@link #onTransition(Booking, BookingStatus, BookingStatus, Actor, String)}, naming the
     * Tenant that caused the transition. Only {@code ProviderAssigned} carries it: the Provider's
     * notification says which agency assigned the job (Requirement MT-5.3). The Tenant's id is the
     * booking's own {@code tenantId}; only the display name is supplied by the caller, since the
     * booking does not store it.
     *
     * @param tenantName the assigning Tenant's name, or null
     */
    public void onTransition(Booking booking, BookingStatus from, BookingStatus to,
                             Actor actor, String reason, String tenantName) {
        switch (to) {
            case CANCELLED, SEARCHING_FAILED -> publishBookingCancelled(booking, from, to, actor, reason);
            case PROVIDER_ASSIGNED -> publishProviderAssigned(booking, tenantName);
            default -> {
                // No state-driven event for this target.
            }
        }
    }

    private void publishBookingCancelled(Booking booking, BookingStatus from, BookingStatus to,
                                         Actor actor, String reason) {
        BookingCancelledEvent event = new BookingCancelledEvent(
                booking.getId(), booking.getReference(),
                booking.getCustomerId(), booking.getProviderId(),
                from, to, actor.id(), actor.role(), reason, booking.getCancellationFee(),
                booking.getCreatedAt(), Instant.now(clock));
        outboxPublisher.publish(
                BookingCancelledEvent.AGGREGATE_TYPE, booking.getId(),
                BookingCancelledEvent.EVENT_TYPE, event);
    }

    private void publishProviderAssigned(Booking booking, String tenantName) {
        ProviderAssignedEvent event = new ProviderAssignedEvent(
                booking.getId(), booking.getReference(),
                booking.getCustomerId(), booking.getProviderId(),
                booking.getCreatedAt(), Instant.now(clock),
                booking.getTenantId(), booking.getTenantId() == null ? null : tenantName);
        outboxPublisher.publish(
                ProviderAssignedEvent.AGGREGATE_TYPE, booking.getId(),
                ProviderAssignedEvent.EVENT_TYPE, event);
    }
}
