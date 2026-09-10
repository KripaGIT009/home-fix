package com.homefix.promotion.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homefix.promotion.service.CouponException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates coupon domain and bean-validation failures into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 *
 * <p>A {@link CouponException} already carries the appropriate HTTP status and stable error code
 * (attribute validation 400, not-found 404, duplicate/concurrency 409, constraint violation 422),
 * so the mapping simply copies those through. No customer PII is included (Requirement 26.4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(CouponException.class)
    public ResponseEntity<ErrorResponseDto> handleCoupon(CouponException ex) {
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
