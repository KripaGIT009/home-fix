package com.homefix.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.chat.api.dto.MessageResponse;
import com.homefix.chat.api.dto.SendMessageRequest;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.service.ChatException;
import com.homefix.chat.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Direct-invocation tests for {@link ChatController}. Because the whole request mapping carries a
 * {@code {bookingId}} path variable and the build does not enable the {@code -parameters} flag,
 * the controller is exercised by direct method calls with the authenticated principal placed on
 * the {@link SecurityContextHolder}. Delegation, message masking pass-through, and the
 * unauthenticated/invalid-principal guards are verified (Requirement 18.7).
 */
class ChatControllerTest {

    private ChatService chatService;
    private ChatController controller;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        controller = new ChatController(chatService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
    }

    private ChatMessage message(UUID bookingId, UUID senderId, String body) {
        return ChatMessage.create(bookingId, senderId, body, Instant.now(),
                Instant.now().plusSeconds(90 * 24 * 3600L));
    }

    @Test
    void send_returns201_andDelegatesToServiceWithAuthenticatedSender() {
        authenticateAs(user.toString());
        UUID booking = UUID.randomUUID();
        when(chatService.sendMessage(eq(booking), eq(user), eq("call me")))
                .thenReturn(message(booking, user, "call me"));

        var response = controller.send(booking, new SendMessageRequest("call me"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().body()).isEqualTo("call me");
        verify(chatService).sendMessage(booking, user, "call me");
    }

    @Test
    void history_returnsMappedMessages() {
        authenticateAs(user.toString());
        UUID booking = UUID.randomUUID();
        when(chatService.readMessages(booking, user))
                .thenReturn(List.of(message(booking, user, "hi"), message(booking, user, "there")));

        List<MessageResponse> history = controller.history(booking);

        assertThat(history).extracting(MessageResponse::body).containsExactly("hi", "there");
    }

    @Test
    void send_withoutAuthentication_isUnauthorized() {
        assertThatThrownBy(() -> controller.send(UUID.randomUUID(), new SendMessageRequest("x")))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> assertThat(((ChatException) e).getErrorCode()).isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void send_withNonUuidPrincipal_isRejected() {
        authenticateAs("not-a-uuid");
        assertThatThrownBy(() -> controller.send(UUID.randomUUID(), new SendMessageRequest("x")))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> assertThat(((ChatException) e).getErrorCode()).isEqualTo("INVALID_PRINCIPAL"));
    }
}
