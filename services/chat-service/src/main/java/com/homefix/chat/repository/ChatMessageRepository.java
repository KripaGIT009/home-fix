package com.homefix.chat.repository;

import java.util.List;
import java.util.UUID;

import com.homefix.chat.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the {@code chat_message} table (Requirement 18.4).
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByBookingIdOrderBySentAtAsc(UUID bookingId);
}
