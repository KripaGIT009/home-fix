package com.homefix.chat.config;

import java.security.Principal;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.service.ChatStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Enforces the participant-only access rule (Requirement 18.7, Property 23) on the real-time read
 * path: a STOMP {@code SUBSCRIBE} to {@code /topic/chat/{bookingId}} is rejected unless the
 * connected principal is the booking's customer or provider. This mirrors, for WebSocket reads,
 * the same check the {@link com.homefix.chat.service.ChatService} applies to REST reads and sends.
 */
@Configuration
public class WebSocketAuthorizationConfig implements WebSocketMessageBrokerConfigurer {

    static final String DESTINATION_PREFIX = "/topic/chat/";

    private final ChatStore chatStore;

    public WebSocketAuthorizationConfig(ChatStore chatStore) {
        this.chatStore = chatStore;
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    return message;
                }
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith(DESTINATION_PREFIX)) {
                    return message;
                }
                UUID bookingId = parseBookingId(destination);
                UUID userId = parsePrincipal(accessor.getUser());
                if (bookingId == null || userId == null || !isParticipant(bookingId, userId)) {
                    throw new org.springframework.messaging.MessagingException(
                            "subscription to chat channel denied: not a participant");
                }
                return message;
            }
        });
    }

    private boolean isParticipant(UUID bookingId, UUID userId) {
        return chatStore.findChannel(bookingId)
                .map(ChatChannel::participants)
                .map(p -> p.isParticipant(userId))
                .orElse(false);
    }

    private static UUID parseBookingId(String destination) {
        try {
            return UUID.fromString(destination.substring(DESTINATION_PREFIX.length()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID parsePrincipal(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
