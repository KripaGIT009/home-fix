package com.homefix.chat.repository;

import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the {@code chat_channel} table. Keyed by {@code bookingId} so a
 * booking maps to exactly one channel (Requirement 18.1).
 */
public interface ChatChannelRepository extends JpaRepository<ChatChannel, UUID> {
}
