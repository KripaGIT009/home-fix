package com.homefix.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * {@link ChatChannel}'s own rules: an activated channel starts ACTIVE, a tombstone starts
 * DEACTIVATED with placeholders for the participants it was not told about, and both are "new"
 * (so Spring Data inserts rather than merges them) until JPA has loaded or persisted the row.
 */
class ChatChannelTest {

    private static final Instant CREATED = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2024-01-02T10:15:00Z");

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @Test
    void activatedChannelIsActiveAndNew() {
        ChatChannel channel = ChatChannel.activate(bookingId, customerId, providerId, CREATED, NOW);

        assertThat(channel.isActive()).isTrue();
        assertThat(channel.getStatus()).isEqualTo(ChannelStatus.ACTIVE);
        assertThat(channel.getActivatedAt()).isEqualTo(NOW);
        assertThat(channel.getDeactivatedAt()).isNull();
        assertThat(channel.getId()).isEqualTo(bookingId);
        assertThat(channel.isNew()).isTrue();
    }

    @Test
    void tombstoneIsBornDeactivatedWithTheGivenParticipants() {
        ChatChannel tombstone = ChatChannel.tombstone(bookingId, customerId, providerId, CREATED, NOW);

        assertThat(tombstone.isActive()).isFalse();
        assertThat(tombstone.getStatus()).isEqualTo(ChannelStatus.DEACTIVATED);
        assertThat(tombstone.getDeactivatedAt()).isEqualTo(NOW);
        assertThat(tombstone.getBookingCreatedAt()).isEqualTo(CREATED);
        assertThat(tombstone.participants()).isEqualTo(new ChannelParticipants(customerId, providerId));
        assertThat(tombstone.isNew()).isTrue();
    }

    @Test
    void tombstoneFillsMissingParticipantsWithTheNilIdAndMissingCreationWithNow() {
        ChatChannel tombstone = ChatChannel.tombstone(bookingId, customerId, null, null, NOW);

        assertThat(tombstone.getCustomerId()).isEqualTo(customerId);
        assertThat(tombstone.getProviderId()).isEqualTo(ChatChannel.UNKNOWN_PARTICIPANT);
        assertThat(ChatChannel.UNKNOWN_PARTICIPANT).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(tombstone.getBookingCreatedAt()).isEqualTo(NOW);
        assertThat(tombstone.participants().isParticipant(providerId)).isFalse();
    }

    @Test
    void channelIsNoLongerNewOnceLoadedOrPersisted() {
        ChatChannel channel = ChatChannel.activate(bookingId, customerId, providerId, CREATED, NOW);

        channel.markPersisted();

        assertThat(channel.isNew()).isFalse();
    }

    @Test
    void channelBuiltByJpaIsNotNew() {
        // JPA instantiates through the no-arg constructor and then fires @PostLoad.
        ChatChannel loaded = new ChatChannel();
        loaded.markPersisted();

        assertThat(loaded.isNew()).isFalse();
        assertThat(new ChatChannel().isNew()).isFalse();
    }
}
