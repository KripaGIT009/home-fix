package com.homefix.customer.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /customers/{id}/addresses} (Requirement 2.2).
 */
public record AddressRequest(

        @Size(max = 100, message = "label must be at most 100 characters")
        String label,

        @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90.0", message = "latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "latitude must be <= 90")
        Double lat,

        @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "longitude must be <= 180")
        Double lng) {
}
