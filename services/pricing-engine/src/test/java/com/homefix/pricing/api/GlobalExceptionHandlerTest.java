package com.homefix.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.homefix.pricing.service.PricingException;
import com.homefix.shared.observability.error.ErrorResponseDto;

/**
 * Unit tests for the pricing {@link GlobalExceptionHandler}: it maps domain and validation
 * exceptions to the shared error envelope, preserving status, error code, details, and the MDC
 * correlation id (Task 4, Task 5).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handlePricingMapsStatusCodeMessageDetailsAndCorrelationId() {
        MDC.put("correlationId", "corr-123");
        PricingException ex = PricingException.overrideOutOfRange(
                "out of range", List.of("floor: 10.00", "ceiling: 500.00"));

        ResponseEntity<ErrorResponseDto> resp = handler.handlePricing(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ErrorResponseDto body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getErrorCode()).isEqualTo("OVERRIDE_OUT_OF_RANGE");
        assertThat(body.getMessage()).isEqualTo("out of range");
        assertThat(body.getCorrelationId()).isEqualTo("corr-123");
        assertThat(body.getDetails()).containsExactly("floor: 10.00", "ceiling: 500.00");
    }

    @Test
    void handlePricingWorksWithoutCorrelationId() {
        ResponseEntity<ErrorResponseDto> resp =
                handler.handlePricing(PricingException.validation("bad"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(resp.getBody().getCorrelationId()).isNull();
    }

    @Test
    void handleValidationCollectsFieldErrors() {
        MDC.put("correlationId", "corr-xyz");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "subcategoryId", "must not be null"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<ErrorResponseDto> resp = handler.handleValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getErrorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(resp.getBody().getMessage()).isEqualTo("Request validation failed");
        assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-xyz");
        assertThat(resp.getBody().getDetails()).contains("subcategoryId: must not be null");
    }
}
