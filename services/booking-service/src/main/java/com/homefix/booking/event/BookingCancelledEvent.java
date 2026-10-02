package com.homefix.booking.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.booking.domain.BookingStatus;

/**
 * Payload of the {@code BookingCancelled} event, written to the transactional outbox in the same
 * transaction as every transition that ends a booking before the job is done (Requirement 22.1,
 * 22.2): CANCELLED from any cancellable state, whoever requested it, and SEARCHING_FAILED when
 * dispatch exhausts every candidate.
 *
 * <p>Consumers (Requirement 17.5, 18.5):
 * <ul>
 *   <li><b>chat-service</b> deactivates the booking's channel. It requires {@code bookingId}.</li>
 *   <li><b>notification-service</b> tells the participants. It requires {@code customerId},
 *       also notifies {@code providerId} when one is assigned, and renders {@code reference}.</li>
 * </ul>
 *
 * <p>{@code status} tells a deliberate cancellation ({@code CANCELLED}) apart from a dispatch
 * failure ({@code SEARCHING_FAILED}).
 *
 * @param bookingId        the cancelled booking (always present)
 * @param reference        the customer-facing booking reference
 * @param customerId       the booking's customer
 * @param providerId       the assigned provider, or null when cancelled before assignment
 * @param previousStatus   the state the booking was cancelled from
 * @param status           the terminal state entered: CANCELLED or SEARCHING_FAILED
 * @param cancelledBy      the user who cancelled, or null for a system-initiated transition
 * @param cancelledByRole  the actor's role (e.g. CUSTOMER, SERVICE_PROVIDER, ADMIN), or the
 *                         originating service name for a system-initiated transition
 * @param reason           the free-text reason recorded in the audit trail, if any
 * @param cancellationFee  the fee applied (Requirement 9.16-9.18); null when no fee policy ran,
 *                         as on SEARCHING_FAILED
 * @param bookingCreatedAt when the booking was created
 * @param occurredAt       when the transition was applied
 */
public record BookingCancelledEvent(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID providerId,
        BookingStatus previousStatus,
        BookingStatus status,
        UUID cancelledBy,
        String cancelledByRole,
        String reason,
        BigDecimal cancellationFee,
        Instant bookingCreatedAt,
        Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "Booking";
    public static final String EVENT_TYPE = "BookingCancelled";
}
