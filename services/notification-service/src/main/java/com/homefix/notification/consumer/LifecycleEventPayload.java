package com.homefix.notification.consumer;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.notification.domain.EventParticipants;

/**
 * Inbound view of any event consumed by the Notification Service (Requirements 16.2, 16.3, 17.5).
 * Producers publish richer payloads; only the user ids needed to address a notification and the
 * non-PII attributes needed to render it are modelled here, and unknown fields are ignored so
 * producers can evolve their payloads independently.
 *
 * <p>Events carry user ids, never contact details: who is notified is decided by the
 * {@code RecipientPolicy}, and their phone number or email is resolved from the Auth Service at
 * send time. The stable {@code eventId} used for deduplication is carried on the Kafka record
 * header, not in this body.
 *
 * <p>Field names follow the producers: booking-service publishes the booking reference as
 * {@code reference} (the older {@code bookingReference} spelling is still accepted),
 * rating-review-service names {@code reviewerId}/{@code revieweeId}, and complaint-service names
 * the new status {@code newStatus}; booking-service's {@code BookingCancelled} carries the terminal
 * booking {@code status} ({@code CANCELLED} or {@code SEARCHING_FAILED}); its
 * {@code ProviderAssigned} names the partner agency that assigned the job as {@code tenantName}
 * (absent when the platform assigned it). The agency's {@code tenantId} is not needed to render
 * anything and is ignored with the other unknown fields.
 *
 * @param bookingId   the booking the event relates to
 * @param reference   human-readable booking reference used in message text (non-PII)
 * @param customerId  the customer on the booking or complaint
 * @param providerId  the provider on the booking (null early in the lifecycle)
 * @param reviewerId  the author of a review
 * @param revieweeId  the subject of a review
 * @param complaintId the complaint a complaint event relates to
 * @param newStatus   a complaint's new status (enum name, non-PII)
 * @param status      a booking's status on a booking event (enum name, non-PII)
 * @param tenantName  the partner agency (Tenant) that assigned the provider, on a Tenant-assigned
 *                    {@code ProviderAssigned} (an organisation's name, not personal data;
 *                    Requirement MT-5.3)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEventPayload(
        UUID bookingId,
        @JsonAlias("bookingReference") String reference,
        UUID customerId,
        UUID providerId,
        UUID reviewerId,
        UUID revieweeId,
        UUID complaintId,
        String newStatus,
        String status,
        String tenantName) {

    /** The user ids this event names, for the recipient policy. */
    public EventParticipants participants() {
        return new EventParticipants(customerId, providerId, reviewerId, revieweeId);
    }
}
