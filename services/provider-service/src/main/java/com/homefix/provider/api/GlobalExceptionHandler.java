package com.homefix.provider.api;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.homefix.provider.service.ProviderException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Translates domain and bean-validation exceptions into the shared {@link ErrorResponseDto}
 * envelope (Task 5), preserving the correlation ID placed on the MDC by the shared
 * {@code CorrelationIdFilter} (Task 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_MDC_KEY = "correlationId";

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponseDto> handleProvider(ProviderException ex) {
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

    /**
     * A missing or unparseable query parameter (e.g. {@code lat=abc} on the internal eligibility
     * search) is a client error in the shared envelope, not Spring's bare default 400.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponseDto> handleBadParameter(Exception ex) {
        String detail = ex instanceof MissingServletRequestParameterException missing
                ? missing.getParameterName() + ": is required"
                : ((MethodArgumentTypeMismatchException) ex).getName() + ": has an invalid value";
        ErrorResponseDto.Builder builder = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY));
        builder.addDetail(detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(builder.build());
    }

    /**
     * A malformed or missing request body is a client error in the shared envelope, not Spring's
     * bare default 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorResponseDto.Builder builder = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request body is missing or malformed")
                .correlationId(MDC.get(CORRELATION_MDC_KEY));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(builder.build());
    }
}
