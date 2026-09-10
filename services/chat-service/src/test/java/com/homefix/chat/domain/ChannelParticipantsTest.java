package com.homefix.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure participant-only access rule (Requirement 18.7, Property 23).
 *
 * <p>Property 23 states access is permitted <em>iff</em> the requesting user is the channel's
 * customer or provider; every other user is denied. These example-based tests pin that contract on
 * the pure class a future property-based test will target.
 */
class ChannelParticipantsTest {

    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final ChannelParticipants participants = new ChannelParticipants(customerId, providerId);

    @Test
    void customerIsParticipant() {
        assertThat(participants.isParticipant(customerId)).isTrue();
    }

    @Test
    void providerIsParticipant() {
        assertThat(participants.isParticipant(providerId)).isTrue();
    }

    @Test
    void anyOtherUserIsNotParticipant() {
        assertThat(participants.isParticipant(UUID.randomUUID())).isFalse();
    }

    @Test
    void nullUserIsNotParticipant() {
        assertThat(participants.isParticipant(null)).isFalse();
    }
}
