package com.homefix.reporting.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.reporting.rbac.ReportAccessDeniedException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates Reporting-service domain and bean-validation exceptions into the shared
 * {@link ErrorResponseDto} envelope (Task 5), preserving the correlation ID placed on the MDC by
 * the shared {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    /** Non-Finance_Admin requesting a restricted report → 403 (Requirement 20.6). */
    @ExceptionHandler(ReportAccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(ReportAccessDeniedException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("REPORT_ACCESS_DENIED")
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Invalid filters (e.g. inverted date range) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(IllegalArgumentException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("INVALID_REPORT_REQUEST")
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
