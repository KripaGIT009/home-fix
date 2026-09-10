package com.homefix.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.chat.domain.ChatMessage;

/**
 * API view of a stored chat message. The {@code body} is already phone-masked (Requirement 18.8).
 */
public record MessageResponse(
        UUID id,
        UUID bookingId,
        UUID senderId,
        String body,
        Instant sentAt) {

    public static MessageResponse from(ChatMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getBookingId(),
                message.getSenderId(),
                message.getBody(),
                message.getSentAt());
    }
}
