package com.homefix.admin.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.admin.audit.InvalidAuditQueryException;
import com.homefix.admin.rbac.ModuleAccessDeniedException;
import com.homefix.admin.sysconfig.SystemConfigValidationException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates Admin-service domain and bean-validation exceptions into the shared
 * {@link ErrorResponseDto} envelope (Task 5), preserving the correlation ID placed on the MDC by
 * the shared {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    /** ADMIN attempting a SUPER_ADMIN-only module (e.g. System Configuration) → 403 (Req 19.7). */
    @ExceptionHandler(ModuleAccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(ModuleAccessDeniedException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("MODULE_ACCESS_DENIED")
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Unknown setting or a value that breaks its rule → 400 listing every problem; nothing changed. */
    @ExceptionHandler(SystemConfigValidationException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidSystemConfig(SystemConfigValidationException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("INVALID_SYSTEM_CONFIG")
                .message(ex.getMessage())
                .details(ex.getProblems())
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Unknown action filter or malformed cursor on the Audit Logs view → 400. */
    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidAuditQuery(InvalidAuditQueryException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("INVALID_AUDIT_QUERY")
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request body is missing or malformed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
