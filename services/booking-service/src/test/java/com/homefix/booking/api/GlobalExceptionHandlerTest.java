package com.homefix.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.InvalidTransitionException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Unit tests for the booking {@link GlobalExceptionHandler}: illegal transitions map to 409 with
 * the from/to detail lines (Requirement 9.2, Property 8), booking exceptions carry their declared
 * status/code, validation errors map to 400, and the MDC correlation id is preserved throughout.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handleInvalidTransitionMapsTo409WithStateDetails() {
        MDC.put("correlationId", "corr-1");
        UUID booking = UUID.randomUUID();
        InvalidTransitionException ex = new InvalidTransitionException(
                booking, BookingStatus.CREATED, BookingStatus.JOB_COMPLETED);

        ResponseEntity<ErrorResponseDto> resp = handler.handleInvalidTransition(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponseDto body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getErrorCode()).isEqualTo("INVALID_BOOKING_TRANSITION");
        assertThat(body.getMessage()).contains("CREATED", "JOB_COMPLETED");
        assertThat(body.getCorrelationId()).isEqualTo("corr-1");
        assertThat(body.getDetails())
                .contains("bookingId: " + booking, "fromState: CREATED", "toState: JOB_COMPLETED");
    }

    @Test
    void handleBookingUsesDeclaredStatusAndCode() {
        ResponseEntity<ErrorResponseDto> resp =
                handler.handleBooking(BookingException.pricingUnavailable());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("PRICING_ENGINE_UNAVAILABLE");
    }

    @Test
    void aLostOptimisticLockMapsTo409BookingChanged() {
        MDC.put("correlationId", "corr-2");

        ResponseEntity<ErrorResponseDto> resp = handler.handleConcurrentChange(
                new org.springframework.orm.ObjectOptimisticLockingFailureException("Booking", UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("BOOKING_CHANGED");
        assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-2");
    }

    @Test
    void handleBookingNotFoundMapsTo404() {
        ResponseEntity<ErrorResponseDto> resp =
                handler.handleBooking(BookingException.notFound("HFX-404"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("BOOKING_NOT_FOUND");
        assertThat(resp.getBody().getMessage()).contains("HFX-404");
    }

    @Test
    void handleValidationCollectsFieldErrors() {
        MDC.put("correlationId", "corr-2");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "reason", "must not be blank"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<ErrorResponseDto> resp = handler.handleValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-2");
        assertThat(resp.getBody().getDetails()).contains("reason: must not be blank");
    }
}
