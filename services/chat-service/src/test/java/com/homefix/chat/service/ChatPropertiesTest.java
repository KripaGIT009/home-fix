package com.homefix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.support.TestDoubles.FixedPresenceRegistry;
import com.homefix.chat.support.TestDoubles.InMemoryChatStore;
import com.homefix.chat.support.TestDoubles.RecordingDeliveryPort;
import com.homefix.chat.support.TestDoubles.RecordingPushPort;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.HttpStatus;

/**
 * Property-based test for the Chat Service correctness property 23 (design.md "Correctness
 * Properties", Requirement 18.7). Runs a minimum of 100 tries and is tagged with the required
 * {@code Feature: homefix-platform, Property N} label.
 *
 * <p>Complements the example-based {@link ChatServiceTest} by asserting the participant-only
 * invariant universally: <em>for any</em> requesting user and either operation (send or read),
 * access is permitted <em>if and only if</em> the user is the channel's customer or provider; every
 * other user receives a 403 Forbidden ({@code CHANNEL_ACCESS_FORBIDDEN}).
 *
 * <p>The real {@link ChatService} is exercised against in-memory ports (no Spring, no DB), which is
 * where the access check actually lives.
 */
class ChatPropertiesTest {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Instant BOOKING_CREATED = Instant.parse("2024-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-02T10:15:00Z"), ZoneOffset.UTC);

    // ============================================================================================
    // Property 23: Chat channel access restriction
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 23: Chat channel access restriction")
    void messageOperationsPermittedIffRequesterIsCustomerOrProvider(
            @ForAll("distinctPair") UUID[] pair,
            @ForAll("requester") RequesterRole role,
            @ForAll boolean read) {

        UUID customerId = pair[0];
        UUID providerId = pair[1];
        UUID bookingId = UUID.randomUUID();

        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = new ChatService(store, new RecordingDeliveryPort(),
                FixedPresenceRegistry.allOnline(), new RecordingPushPort(), RETENTION, FIXED_CLOCK);
        store.saveChannel(ChatChannel.activate(
                bookingId, customerId, providerId, BOOKING_CREATED, BOOKING_CREATED));

        UUID requester = switch (role) {
            case CUSTOMER -> customerId;
            case PROVIDER -> providerId;
            case STRANGER -> UUID.randomUUID(); // guaranteed distinct from customer/provider
        };
        boolean isParticipant = role != RequesterRole.STRANGER;

        if (isParticipant) {
            // A participant may both send and read without any access error.
            if (read) {
                assertThat(svc.readMessages(bookingId, requester)).isEmpty();
            } else {
                assertThat(svc.sendMessage(bookingId, requester, "hello").getSenderId())
                        .isEqualTo(requester);
            }
        } else {
            // A non-participant is denied with a 403 CHANNEL_ACCESS_FORBIDDEN, for either operation.
            assertThatThrownBy(() -> {
                if (read) {
                    svc.readMessages(bookingId, requester);
                } else {
                    svc.sendMessage(bookingId, requester, "hello");
                }
            })
                    .isInstanceOf(ChatException.class)
                    .satisfies(ex -> {
                        ChatException ce = (ChatException) ex;
                        assertThat(ce.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ce.getErrorCode()).isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
                    });
        }
    }

    enum RequesterRole {
        CUSTOMER, PROVIDER, STRANGER
    }

    @Provide
    Arbitrary<RequesterRole> requester() {
        return Arbitraries.of(RequesterRole.class);
    }

    /** A customer/provider pair guaranteed to be two distinct users. */
    @Provide
    Arbitrary<UUID[]> distinctPair() {
        Arbitrary<Long> lo = Arbitraries.longs().between(1, 1_000_000);
        return lo.map(l -> new UUID[] {new UUID(0L, l), new UUID(1L, l)});
    }
}
