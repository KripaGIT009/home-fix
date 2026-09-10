package com.homefix.customer.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.customer.booking.BookingLookupException;
import com.homefix.customer.service.CustomerException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(CustomerException.class)
    public ResponseEntity<ErrorResponseDto> handleCustomer(CustomerException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(BookingLookupException.class)
    public ResponseEntity<ErrorResponseDto> handleBookingLookup(BookingLookupException ex) {
        // Cannot confirm the address is free -> fail the deletion safely (Requirement 2.6).
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("BOOKING_SERVICE_UNAVAILABLE")
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
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
}
