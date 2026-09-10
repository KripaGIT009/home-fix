package com.homefix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.support.TestDoubles.FixedPresenceRegistry;
import com.homefix.chat.support.TestDoubles.InMemoryChatStore;
import com.homefix.chat.support.TestDoubles.RecordingDeliveryPort;
import com.homefix.chat.support.TestDoubles.RecordingPushPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Behavioural tests for the Chat Service (Requirement 18, Property 23): channel lifecycle,
 * participant-only access (403), deactivated-channel rejection, 90-day retention anchoring,
 * offline-recipient push, and phone masking on stored bodies.
 */
class ChatServiceTest {

    private static final Duration NINETY_DAYS = Duration.ofDays(90);
    private static final Instant BOOKING_CREATED = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2024-01-02T10:15:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private ChatService service(InMemoryChatStore store, RecordingDeliveryPort delivery,
                                RecordingPushPort push, boolean recipientOnline) {
        return new ChatService(store, delivery,
                recipientOnline ? FixedPresenceRegistry.allOnline() : FixedPresenceRegistry.allOffline(),
                push, NINETY_DAYS, FIXED_CLOCK);
    }

    private ChatChannel seedActiveChannel(InMemoryChatStore store) {
        return store.saveChannel(ChatChannel.activate(
                bookingId, customerId, providerId, BOOKING_CREATED, BOOKING_CREATED));
    }

    // ----- Channel lifecycle (Requirement 18.1, 18.5) -----

    @Test
    void activateChannelCreatesActiveChannelWithParticipantsAndBookingCreationDate() {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        ChatChannel channel = svc.activateChannel(bookingId, customerId, providerId, BOOKING_CREATED);

        assertThat(channel.isActive()).isTrue();
        assertThat(channel.getCustomerId()).isEqualTo(customerId);
        assertThat(channel.getProviderId()).isEqualTo(providerId);
        assertThat(channel.getBookingCreatedAt()).isEqualTo(BOOKING_CREATED);
        assertThat(store.findChannel(bookingId)).isPresent();
    }

    @Test
    void activateChannelIsIdempotentOnRedelivery() {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        ChatChannel first = svc.activateChannel(bookingId, customerId, providerId, BOOKING_CREATED);
        ChatChannel second = svc.activateChannel(bookingId, customerId, providerId, BOOKING_CREATED);

        // Same channel returned; the original activation timestamp is preserved.
        assertThat(second.getActivatedAt()).isEqualTo(first.getActivatedAt());
        assertThat(store.findChannel(bookingId)).isPresent();
    }

    @Test
    void deactivateChannelMarksChannelDeactivated() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        svc.deactivateChannel(bookingId);

        assertThat(store.findChannel(bookingId)).get()
                .satisfies(c -> {
                    assertThat(c.isActive()).isFalse();
                    assertThat(c.getDeactivatedAt()).isEqualTo(NOW);
                });
    }

    @Test
    void deactivateChannelIsNoOpWhenAbsentOrAlreadyDeactivated() {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        // Absent channel: no exception.
        svc.deactivateChannel(bookingId);

        // Already deactivated: second call keeps original deactivatedAt.
        seedActiveChannel(store);
        svc.deactivateChannel(bookingId);
        Instant firstDeactivation = store.findChannel(bookingId).orElseThrow().getDeactivatedAt();
        svc.deactivateChannel(bookingId);
        assertThat(store.findChannel(bookingId).orElseThrow().getDeactivatedAt())
                .isEqualTo(firstDeactivation);
    }

    // ----- Access control: non-participant -> 403 (Requirement 18.7, Property 23) -----

    @Test
    void nonParticipantSendReceives403() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        ChatException ex = catchThrowableOfType(
                () -> svc.sendMessage(bookingId, stranger, "hello"), ChatException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
        assertThat(store.messages).isEmpty();
    }

    @Test
    void nonParticipantReadReceives403() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        ChatException ex = catchThrowableOfType(
                () -> svc.readMessages(bookingId, stranger), ChatException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bothParticipantsMaySendAndRead() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);

        svc.sendMessage(bookingId, customerId, "from customer");
        svc.sendMessage(bookingId, providerId, "from provider");

        assertThat(svc.readMessages(bookingId, customerId)).hasSize(2);
        assertThat(svc.readMessages(bookingId, providerId)).hasSize(2);
    }

    // ----- Deactivated channel send rejection (Requirement 18.6) -----

    @Test
    void sendToDeactivatedChannelIsRejectedWithDescriptiveError() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);
        svc.deactivateChannel(bookingId);

        ChatException ex = catchThrowableOfType(
                () -> svc.sendMessage(bookingId, customerId, "still there?"), ChatException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("CHANNEL_DEACTIVATED");
        assertThat(ex.getMessage()).contains("no longer active");
        assertThat(store.messages).isEmpty();
    }

    @Test
    void readIsAllowedAfterDeactivationSoRetainedHistoryStaysReadable() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);
        svc.sendMessage(bookingId, customerId, "before close");
        svc.deactivateChannel(bookingId);

        // Reading history after deactivation still works (Requirement 18.4).
        assertThat(svc.readMessages(bookingId, providerId)).hasSize(1);
    }

    @Test
    void sendToMissingChannelReturns404() {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), false);

        assertThatThrownBy(() -> svc.sendMessage(bookingId, customerId, "hi"))
                .isInstanceOf(ChatException.class)
                .satisfies(t -> assertThat(((ChatException) t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ----- 90-day retention (Requirement 18.4) -----

    @Test
    void storedMessageRetainedForNinetyDaysFromBookingCreationDate() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);

        ChatMessage message = svc.sendMessage(bookingId, customerId, "keep me");

        // retainUntil is anchored to booking creation, not send time, and is >= 90 days out.
        assertThat(message.getRetainUntil()).isEqualTo(BOOKING_CREATED.plus(NINETY_DAYS));
        assertThat(Duration.between(BOOKING_CREATED, message.getRetainUntil()))
                .isGreaterThanOrEqualTo(NINETY_DAYS);
    }

    @Test
    void retentionSurvivesChannelDeactivation() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);
        ChatMessage message = svc.sendMessage(bookingId, customerId, "durable");

        svc.deactivateChannel(bookingId);

        // The stored message and its retention window are untouched by deactivation.
        List<ChatMessage> history = svc.readMessages(bookingId, customerId);
        assertThat(history).singleElement()
                .satisfies(m -> assertThat(m.getRetainUntil()).isEqualTo(message.getRetainUntil()));
    }

    // ----- Real-time delivery + offline push (Requirement 18.2, 18.3) -----

    @Test
    void messageIsDeliveredOverRealtimeTransport() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        RecordingDeliveryPort delivery = new RecordingDeliveryPort();
        ChatService svc = service(store, delivery, new RecordingPushPort(), true);

        svc.sendMessage(bookingId, customerId, "ping");

        assertThat(delivery.delivered).hasSize(1);
        assertThat(delivery.delivered.get(0).getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void offlineRecipientTriggersPushToTheOtherParticipant() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        RecordingPushPort push = new RecordingPushPort();
        ChatService svc = service(store, new RecordingDeliveryPort(), push, false);

        svc.sendMessage(bookingId, customerId, "are you there?");

        assertThat(push.pushes.get()).isEqualTo(1);
        // Customer sent, so the provider is the (offline) recipient who is pushed.
        assertThat(push.lastRecipient).isEqualTo(providerId);
        assertThat(push.lastBooking).isEqualTo(bookingId);
    }

    @Test
    void onlineRecipientIsNotPushed() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        RecordingPushPort push = new RecordingPushPort();
        ChatService svc = service(store, new RecordingDeliveryPort(), push, true);

        svc.sendMessage(bookingId, providerId, "hi");

        assertThat(push.pushes.get()).isZero();
    }

    // ----- Phone masking on stored body (Requirement 18.8) -----

    @Test
    void storedMessageBodyHasPhoneNumberMasked() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);

        ChatMessage message = svc.sendMessage(bookingId, customerId, "call me on +91 98765 43210");

        assertThat(message.getBody()).doesNotContain("98765");
        assertThat(message.getBody()).doesNotContain("43210");
        assertThat(message.getBody()).contains("*");
    }

    @Test
    void blankMessageIsRejected() {
        InMemoryChatStore store = new InMemoryChatStore();
        seedActiveChannel(store);
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(), true);

        assertThatThrownBy(() -> svc.sendMessage(bookingId, customerId, "   "))
                .isInstanceOf(ChatException.class)
                .satisfies(t -> assertThat(((ChatException) t).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
