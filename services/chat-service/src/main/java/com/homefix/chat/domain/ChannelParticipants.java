package com.homefix.chat.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure, dependency-free value object holding the two users linked to a booking's chat channel
 * and the sole authority for the participant-only access rule (Requirement 18.7, Property 23).
 *
 * <p>Kept free of Spring, JPA, and I/O so it can be exhaustively exercised by unit tests and a
 * future property-based test: <em>for any</em> requesting user, access is permitted <em>if and
 * only if</em> the user is the channel's customer or provider; every other user is denied.
 *
 * @param customerId the customer on the booking
 * @param providerId the provider on the booking
 */
public record ChannelParticipants(UUID customerId, UUID providerId) {

    public ChannelParticipants {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(providerId, "providerId");
    }

    /**
     * @return {@code true} iff {@code userId} is the customer or the provider on the booking. A
     *         {@code null} user is never a participant.
     */
    public boolean isParticipant(UUID userId) {
        if (userId == null) {
            return false;
        }
        return userId.equals(customerId) || userId.equals(providerId);
    }
}
