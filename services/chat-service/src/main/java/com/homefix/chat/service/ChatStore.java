package com.homefix.chat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;

/**
 * Persistence seam for the Chat Service. Abstracting the two repositories behind one interface
 * lets {@link ChatService} be unit-tested against a fast in-memory double with no Spring or JPA,
 * while production uses the JPA-backed implementation.
 */
public interface ChatStore {

    Optional<ChatChannel> findChannel(UUID bookingId);

    ChatChannel saveChannel(ChatChannel channel);

    ChatMessage saveMessage(ChatMessage message);

    /** Messages for a booking in chronological order (Requirement 18.4). */
    List<ChatMessage> findMessages(UUID bookingId);
}
