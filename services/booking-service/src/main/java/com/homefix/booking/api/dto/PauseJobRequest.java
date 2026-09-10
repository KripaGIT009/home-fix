package com.homefix.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for pausing a job. A mandatory reason of 1-500 characters is required
 * (Requirement 11.5).
 */
public record PauseJobRequest(
        @NotBlank(message = "a pause reason is required")
        @Size(min = 1, max = 500, message = "pause reason must be 1-500 characters")
        String reason) {
}
