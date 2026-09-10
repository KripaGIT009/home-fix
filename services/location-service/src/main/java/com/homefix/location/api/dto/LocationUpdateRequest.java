package com.homefix.location.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /locations/{bookingId}}: the reporting Provider and their
 * current GPS coordinates (Requirement 10.1).
 */
public record LocationUpdateRequest(
        @NotNull(message = "providerId is required")
        UUID providerId,

        @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90.0", message = "latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "latitude must be <= 90")
        Double latitude,

        @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "longitude must be <= 180")
        Double longitude) {
}
