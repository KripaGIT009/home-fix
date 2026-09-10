package com.homefix.chat.delivery;

import com.homefix.chat.domain.ChatMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket-backed {@link MessageDeliveryPort}. Publishes each message to the per-booking STOMP
 * destination {@code /topic/chat/{bookingId}}; the WebSocket broker fans it out to the connected
 * participant sessions subscribed to that booking (Requirement 18.2).
 *
 * <p>Only the two booking participants are ever subscribed to a booking's destination, because
 * subscription is gated by the participant-only access rule (Property 23). The message body has
 * already been phone-masked upstream (Requirement 18.8).
 */
@Component
public class WebSocketMessageDeliveryAdapter implements MessageDeliveryPort {

    static final String DESTINATION_PREFIX = "/topic/chat/";

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketMessageDeliveryAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void deliver(ChatMessage message) {
        messagingTemplate.convertAndSend(DESTINATION_PREFIX + message.getBookingId(), message);
    }
}
