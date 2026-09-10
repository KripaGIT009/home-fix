package com.homefix.provider.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /providers/{id}/emergency-availability} (Requirement 4.6).
 */
public record EmergencyAvailabilityRequest(
        @NotNull(message = "emergencyAvailable is required")
        Boolean emergencyAvailable) {
}
