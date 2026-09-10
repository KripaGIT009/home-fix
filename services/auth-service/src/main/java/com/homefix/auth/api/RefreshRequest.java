package com.homefix.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /auth/token/refresh} (Requirement 1.9).
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
