package com.homefix.chat.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.homefix.chat.api.dto.MessageResponse;
import com.homefix.chat.config.ChatProperties;
import com.homefix.chat.delivery.WebSocketMessageDeliveryAdapter;
import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.presence.InMemoryPresenceRegistry;
import com.homefix.chat.push.LoggingNotificationPushAdapter;
import com.homefix.chat.repository.ChatChannelRepository;
import com.homefix.chat.repository.ChatMessageRepository;
import com.homefix.chat.service.ChatException;
import com.homefix.chat.service.JpaChatStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for the Chat Service adapters, store, presence registry, response DTO, tunable
 * properties, and domain exception factories (Requirement 18).
 */
class ChatAdaptersConfigTest {

    private ChatMessage message(UUID bookingId) {
        return ChatMessage.create(bookingId, UUID.randomUUID(), "hi", Instant.now(),
                Instant.now().plus(Duration.ofDays(90)));
    }

    @Test
    void loggingPushAdapter_notifyUnreadMessage_doesNotThrow() {
        assertThatCode(() -> new LoggingNotificationPushAdapter()
                .notifyUnreadMessage(UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void presenceRegistry_marksOnlineAndOffline() {
        InMemoryPresenceRegistry registry = new InMemoryPresenceRegistry();
        UUID booking = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        assertThat(registry.isOnline(booking, user)).isFalse();
        registry.markOnline(booking, user);
        assertThat(registry.isOnline(booking, user)).isTrue();
        registry.markOffline(booking, user);
        assertThat(registry.isOnline(booking, user)).isFalse();
    }

    @Test
    void webSocketDelivery_sendsToPerBookingStompDestination() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        WebSocketMessageDeliveryAdapter adapter = new WebSocketMessageDeliveryAdapter(template);
        UUID booking = UUID.randomUUID();
        ChatMessage msg = message(booking);

        adapter.deliver(msg);

        verify(template).convertAndSend(eq("/topic/chat/" + booking), eq((Object) msg));
    }

    @Test
    void jpaChatStore_delegatesToRepositories() {
        ChatChannelRepository channelRepo = mock(ChatChannelRepository.class);
        ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
        JpaChatStore store = new JpaChatStore(channelRepo, messageRepo);
        UUID booking = UUID.randomUUID();
        ChatChannel channel = ChatChannel.activate(booking, UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), Instant.now());
        ChatMessage msg = message(booking);

        when(channelRepo.findById(booking)).thenReturn(Optional.of(channel));
        when(channelRepo.save(channel)).thenReturn(channel);
        when(messageRepo.save(msg)).thenReturn(msg);
        when(messageRepo.findByBookingIdOrderBySentAtAsc(booking)).thenReturn(List.of(msg));

        assertThat(store.findChannel(booking)).contains(channel);
        assertThat(store.saveChannel(channel)).isEqualTo(channel);
        assertThat(store.saveMessage(msg)).isEqualTo(msg);
        assertThat(store.findMessages(booking)).containsExactly(msg);
    }

    @Test
    void messageResponse_mapsFromDomain() {
        UUID booking = UUID.randomUUID();
        ChatMessage msg = message(booking);
        MessageResponse response = MessageResponse.from(msg);
        assertThat(response.id()).isEqualTo(msg.getId());
        assertThat(response.bookingId()).isEqualTo(booking);
        assertThat(response.senderId()).isEqualTo(msg.getSenderId());
        assertThat(response.body()).isEqualTo("hi");
    }

    @Test
    void chatProperties_exposeDefaultsAndRoundTrip() {
        ChatProperties p = new ChatProperties();
        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(p.getOfflinePushWindow()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.getPushProvider()).isEqualTo("log");
        assertThat(p.getTopics().getProviderAccepted()).isEqualTo("ProviderAccepted");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("PaymentCompleted");
        assertThat(p.getTopics().getBookingCancelled()).isEqualTo("BookingCancelled");

        p.setRetention(Duration.ofDays(120));
        p.setOfflinePushWindow(Duration.ofSeconds(30));
        p.setPushProvider("notification");
        ChatProperties.Topics t = new ChatProperties.Topics();
        t.setProviderAccepted("pa");
        t.setPaymentCompleted("pc");
        t.setBookingCancelled("bc");
        p.setTopics(t);

        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(120));
        assertThat(p.getOfflinePushWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getPushProvider()).isEqualTo("notification");
        assertThat(p.getTopics().getProviderAccepted()).isEqualTo("pa");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("pc");
        assertThat(p.getTopics().getBookingCancelled()).isEqualTo("bc");
    }

    @Test
    void chatException_factoriesCarryStatusAndErrorCode() {
        assertThat(ChatException.channelNotFound("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ChatException.forbidden("x").getErrorCode()).isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
        assertThat(ChatException.channelDeactivated("x").getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ChatException.validation("x").getErrorCode()).isEqualTo("VALIDATION_ERROR");
        ChatException withDetails = new ChatException(HttpStatus.BAD_REQUEST, "E", "m",
                List.of("d1"));
        assertThat(withDetails.getDetails()).containsExactly("d1");
    }
}
