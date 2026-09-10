package com.homefix.notification.consumer;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of any of the 11 booking-lifecycle events consumed by the Notification Service
 * (Requirement 17.5). Producers publish richer payloads; only the fields needed to address and
 * render a notification are modelled here, and unknown fields are ignored so producers can evolve
 * their payloads independently.
 *
 * <p>The stable {@code eventId} used for deduplication is carried on the Kafka record header, not
 * in this body; this payload supplies the recipient and non-PII rendering attributes.
 *
 * @param bookingId        the booking the event relates to
 * @param bookingReference human-readable booking reference used in message text (non-PII)
 * @param recipientUserId  explicit recipient; falls back to {@code customerId} when absent
 * @param customerId       the customer on the booking
 * @param providerId       the provider on the booking (may be null early in the lifecycle)
 * @param mobileNumber     recipient mobile number for the SMS channel (PII)
 * @param emailAddress     recipient email for the email channel (PII)
 * @param deviceToken      recipient device token for the push channel
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEventPayload(
        UUID bookingId,
        String bookingReference,
        UUID recipientUserId,
        UUID customerId,
        UUID providerId,
        String mobileNumber,
        String emailAddress,
        String deviceToken) {

    /** The user the notification should be addressed to. */
    public UUID resolveRecipient() {
        return recipientUserId != null ? recipientUserId : customerId;
    }
}
