package com.homefix.verification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body carrying a received background-check result (Requirement 5.6).
 */
public record BackgroundCheckResultRequest(
        @NotBlank(message = "result is required")
        @Size(max = 2000, message = "result must be at most 2000 characters")
        String result) {
}
