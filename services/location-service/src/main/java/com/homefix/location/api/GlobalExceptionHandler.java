package com.homefix.location.api;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.location.service.LocationException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    /**
     * Location domain errors (rate-limited, terminated feed, missing location) carry their own
     * HTTP status and error code (Requirements 10.1, 10.5, 10.7).
     */
    @ExceptionHandler(LocationException.class)
    public ResponseEntity<ErrorResponseDto> handleLocation(LocationException ex) {
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
        return ResponseEntity.badRequest().body(builder.build());
    }
}
