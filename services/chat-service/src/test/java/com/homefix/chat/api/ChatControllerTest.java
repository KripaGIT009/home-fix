package com.homefix.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.homefix.chat.api.dto.MessageResponse;
import com.homefix.chat.api.dto.SendMessageRequest;
import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.service.ChatException;
import com.homefix.chat.service.ChatService;
import com.homefix.chat.support.TestDoubles.FixedPresenceRegistry;
import com.homefix.chat.support.TestDoubles.InMemoryChatStore;
import com.homefix.chat.support.TestDoubles.RecordingDeliveryPort;
import com.homefix.chat.support.TestDoubles.RecordingPushPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.http.HttpStatus;
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

    // ----- Participant-only access reaches the REST layer (Requirement 18.7, Property 23) -------
    //
    // The participant rule lives in ChatService and is already unit-tested there. These tests
    // assert the REST path actually applies it: the controller resolves the caller from the JWT
    // (never from the request) and lets the 403 surface, on BOTH handlers. The controller is wired
    // to a real ChatService over the shared in-memory doubles rather than a mock, so a regression
    // that dropped the check would fail here instead of being stubbed away.

    private static final Instant BOOKING_CREATED = Instant.parse("2024-01-01T00:00:00Z");

    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    private ChatController controllerOverRealService(InMemoryChatStore store) {
        ChatService real = new ChatService(store, new RecordingDeliveryPort(),
                FixedPresenceRegistry.allOffline(), new RecordingPushPort(), Duration.ofDays(90),
                Clock.fixed(Instant.parse("2024-01-02T10:15:00Z"), ZoneOffset.UTC));
        return new ChatController(real);
    }

    private InMemoryChatStore storeWithActiveChannel(UUID bookingId) {
        InMemoryChatStore store = new InMemoryChatStore();
        store.saveChannel(ChatChannel.activate(
                bookingId, customerId, providerId, BOOKING_CREATED, BOOKING_CREATED));
        return store;
    }

    @Test
    void send_byNonParticipant_isForbidden() {
        UUID bookingId = UUID.randomUUID();
        InMemoryChatStore store = storeWithActiveChannel(bookingId);
        ChatController real = controllerOverRealService(store);
        authenticateAs(UUID.randomUUID().toString());

        assertThatThrownBy(() -> real.send(bookingId, new SendMessageRequest("let me in")))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> {
                    assertThat(((ChatException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ChatException) e).getErrorCode())
                            .isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
                });
        assertThat(store.messages).isEmpty();
    }

    @Test
    void history_byNonParticipant_isForbidden() {
        UUID bookingId = UUID.randomUUID();
        ChatController real = controllerOverRealService(storeWithActiveChannel(bookingId));
        authenticateAs(UUID.randomUUID().toString());

        assertThatThrownBy(() -> real.history(bookingId))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> assertThat(((ChatException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void send_byTheBookingsCustomer_isAllowed() {
        UUID bookingId = UUID.randomUUID();
        InMemoryChatStore store = storeWithActiveChannel(bookingId);
        ChatController real = controllerOverRealService(store);
        authenticateAs(customerId.toString());

        var response = real.send(bookingId, new SendMessageRequest("on my way"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(store.messages).hasSize(1);
    }

    @Test
    void history_byTheBookingsProvider_isAllowed() {
        UUID bookingId = UUID.randomUUID();
        InMemoryChatStore store = storeWithActiveChannel(bookingId);
        ChatController real = controllerOverRealService(store);
        authenticateAs(customerId.toString());
        real.send(bookingId, new SendMessageRequest("hello"));

        authenticateAs(providerId.toString());
        List<MessageResponse> history = real.history(bookingId);

        assertThat(history).extracting(MessageResponse::body).containsExactly("hello");
    }
}
