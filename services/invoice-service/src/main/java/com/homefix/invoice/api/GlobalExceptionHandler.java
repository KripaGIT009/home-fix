package com.homefix.invoice.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.invoice.service.InvoiceException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and bean-validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 *
 * <p>Copied in shape from the sibling services' handlers (e.g.
 * {@code com.homefix.provider.api.GlobalExceptionHandler}) so the Invoice Service — which until now
 * had no advice at all — returns the same error body as every other HomeFix service instead of
 * Boot's default whitelabel response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(InvoiceException.class)
    public ResponseEntity<ErrorResponseDto> handleInvoice(InvoiceException ex) {
        ErrorResponseDto.Builder builder = ErrorResponseDto.builder()
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(MDC.get(CORRELATION_MDC_KEY));
        ex.getDetails().forEach(builder::addDetail);
        return ResponseEntity.status(ex.getStatus()).body(builder.build());
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
