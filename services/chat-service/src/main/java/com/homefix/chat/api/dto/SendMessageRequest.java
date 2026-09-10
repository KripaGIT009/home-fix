package com.homefix.chat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for sending a chat message. The body is phone-masked server-side before storage
 * and delivery (Requirement 18.8); the sender is derived from the authenticated principal, never
 * from the request, so a caller cannot impersonate another participant.
 */
public record SendMessageRequest(
        @NotBlank(message = "message body must not be blank")
        @Size(max = 4000, message = "message body must be at most 4000 characters")
        String body) {
}
