package com.homefix.notification.domain;

import java.util.UUID;

/**
 * The user ids an inbound event names, as published by its producer. Events carry ids only —
 * never phone numbers or email addresses — so any of these may be null depending on the event.
 *
 * @param customerId the customer on the booking or complaint
 * @param providerId the provider assigned to the booking
 * @param reviewerId the author of a review
 * @param revieweeId the subject of a review
 */
public record EventParticipants(UUID customerId, UUID providerId, UUID reviewerId, UUID revieweeId) {
}
