package com.homefix.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.homefix.chat.service.ChatException;
import com.homefix.shared.observability.error.ErrorResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Unit tests for the Chat Service {@link GlobalExceptionHandler}: a domain {@link ChatException}
 * maps to its status and stable error code (a non-participant → 403 CHANNEL_ACCESS_FORBIDDEN,
 * Property 23), and bean-validation failures map to a 400 VALIDATION_ERROR envelope.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void chatException_mapsToStatusAndErrorCode() {
        ResponseEntity<ErrorResponseDto> response =
                handler.handleChat(ChatException.forbidden("not a participant"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().getErrorCode()).isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
    }

    @Test
    void chatException_includesDetails() {
        ChatException ex = new ChatException(HttpStatus.CONFLICT, "CHANNEL_DEACTIVATED",
                "closed", List.of("channel is not active"));
        ResponseEntity<ErrorResponseDto> response = handler.handleChat(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void validationException_mapsToBadRequestEnvelope() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors())
                .thenReturn(List.of(new FieldError("req", "body", "must not be blank")));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponseDto> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
    }
}
