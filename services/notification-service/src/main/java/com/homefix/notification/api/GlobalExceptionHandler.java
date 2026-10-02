package com.homefix.notification.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.notification.template.InvalidTemplateException;
import com.homefix.notification.template.TemplateNotFoundException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates Notification-service API exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4). The service's only HTTP surface is the Admin Portal's
 * template management; everything else it does is Kafka-driven.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleNotFound(TemplateNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponseDto.builder()
                .errorCode("TEMPLATE_NOT_FOUND")
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build());
    }

    /** Unknown placeholder, blank/oversized text, or a subject on SMS → 400 listing every problem. */
    @ExceptionHandler(InvalidTemplateException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalid(InvalidTemplateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponseDto.builder()
                .errorCode("INVALID_TEMPLATE")
                .message(ex.getMessage())
                .details(ex.getProblems())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request body is missing or malformed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build());
    }
}
