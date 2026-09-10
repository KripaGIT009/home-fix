package com.homefix.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.service.ChatStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * Verifies the WebSocket subscribe-time participant-only rule (Requirement 18.7, Property 23): a
 * STOMP SUBSCRIBE to {@code /topic/chat/{bookingId}} is allowed only for the booking's customer or
 * provider; a non-participant is denied, and non-subscribe frames pass through untouched.
 */
class WebSocketAuthorizationConfigTest {

    private ChatStore chatStore;
    private ChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    private UUID booking;
    private UUID customer;
    private UUID provider;

    @BeforeEach
    void setUp() {
        chatStore = mock(ChatStore.class);
        booking = UUID.randomUUID();
        customer = UUID.randomUUID();
        provider = UUID.randomUUID();
        ChatChannel ch = ChatChannel.activate(booking, customer, provider, Instant.now(), Instant.now());
        when(chatStore.findChannel(booking)).thenReturn(Optional.of(ch));

        WebSocketAuthorizationConfig config = new WebSocketAuthorizationConfig(chatStore);
        ChannelRegistration registration = mock(ChannelRegistration.class);
        config.configureClientInboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        interceptor = captor.getValue();
    }

    private Message<?> subscribe(String destination, UUID principalId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principalId != null) {
            accessor.setUser(namedPrincipal(principalId.toString()));
        }
        return org.springframework.messaging.support.MessageBuilder
                .createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Principal namedPrincipal(String name) {
        return () -> name;
    }

    @Test
    void participantSubscription_isAllowed() {
        Message<?> message = subscribe("/topic/chat/" + booking, customer);
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void nonParticipantSubscription_isDenied() {
        Message<?> message = subscribe("/topic/chat/" + booking, UUID.randomUUID());
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void missingPrincipal_isDenied() {
        Message<?> message = subscribe("/topic/chat/" + booking, null);
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void subscriptionToUnrelatedDestination_passesThrough() {
        Message<?> message = subscribe("/topic/other", customer);
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void nonSubscribeFrame_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/topic/chat/" + booking);
        Message<?> message = org.springframework.messaging.support.MessageBuilder
                .createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void malformedBookingIdInDestination_isDenied() {
        Message<?> message = subscribe("/topic/chat/not-a-uuid", customer);
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }
}
