package com.homefix.chat.delivery;

import com.homefix.chat.domain.ChatMessage;

/**
 * Port for pushing a stored message to a connected recipient over the real-time transport
 * (WebSocket), targeting sub-second delivery under up to 500 concurrent channels
 * (Requirement 18.2).
 *
 * <p>Modelled as a port so the delivery mechanism (STOMP WebSocket broker, raw WebSocket, SSE)
 * can change without touching chat business logic, and so delivery can be asserted in unit tests.
 */
public interface MessageDeliveryPort {

    /**
     * Delivers {@code message} to the recipient's live session for the booking. The body has
     * already been phone-masked before it reaches this port (Requirement 18.8).
     */
    void deliver(ChatMessage message);
}
