package com.homefix.complaint.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.complaint.service.ComplaintException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and bean-validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(ComplaintException.class)
    public ResponseEntity<ErrorResponseDto> handleComplaint(ComplaintException ex) {
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
