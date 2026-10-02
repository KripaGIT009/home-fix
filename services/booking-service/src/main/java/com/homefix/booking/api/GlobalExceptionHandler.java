package com.homefix.booking.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.InvalidTransitionException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    /** Illegal booking state transitions return 409 Conflict (Requirement 9.2, Property 8). */
    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidTransition(InvalidTransitionException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("INVALID_BOOKING_TRANSITION")
                .message("Illegal booking state transition from " + ex.getFromState()
                        + " to " + ex.getToState())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .addDetail("bookingId: " + ex.getBookingId())
                .addDetail("fromState: " + ex.getFromState())
                .addDetail("toState: " + ex.getToState())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BookingException.class)
    public ResponseEntity<ErrorResponseDto> handleBooking(BookingException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        ErrorResponseDto.Builder builder = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY));
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> builder.addDetail(fe.getField() + ": " + fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(builder.build());
    }

    /**
     * A query or path parameter that does not convert to its declared type (e.g.
     * {@code ?page=abc}). Spring's default answer is a bare 400 without this service's envelope,
     * so the client could not read an {@code errorCode}; it is the same client mistake as a
     * failed bean validation and gets the same code.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "a valid value" : ex.getRequiredType().getSimpleName();
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .addDetail(ex.getName() + ": must be " + expected)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
