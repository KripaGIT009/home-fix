package com.homefix.chat.api;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.homefix.chat.api.dto.MessageResponse;
import com.homefix.chat.api.dto.SendMessageRequest;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.service.ChatException;
import com.homefix.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for in-app chat (Requirement 18).
 *
 * <p>The sender/reader is always the authenticated principal populated by the shared
 * {@code JwtValidationFilter} (Task 4); the participant-only 403 rule is enforced in the
 * {@link ChatService} against the channel's stored {@code (customerId, providerId)}
 * (Requirement 18.7, Property 23). Message bodies are phone-masked server-side (Requirement 18.8).
 */
@RestController
@RequestMapping("/chat/channels/{bookingId}/messages")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** Send a message on the booking's channel (Requirement 18.2, 18.6, 18.7). */
    @PostMapping
    public ResponseEntity<MessageResponse> send(@PathVariable UUID bookingId,
                                                @Valid @RequestBody SendMessageRequest req) {
        ChatMessage message = chatService.sendMessage(bookingId, currentUser(), req.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(message));
    }

    /** Read the booking's message history (Requirement 18.4, 18.7). */
    @GetMapping
    public List<MessageResponse> history(@PathVariable UUID bookingId) {
        return chatService.readMessages(bookingId, currentUser()).stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * @return the authenticated user's ID, taken from the principal name populated by the shared
     *         {@code JwtValidationFilter} (Task 4).
     */
    private UUID currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
