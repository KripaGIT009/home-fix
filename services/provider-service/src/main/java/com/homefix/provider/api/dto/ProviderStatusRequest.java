package com.homefix.provider.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /admin/providers/{id}/status} (Requirement 19.2): one of the
 * portal's {@code ACTIVE | SUSPENDED | DEACTIVATED}. Kept as a string so an unknown value is a
 * {@code VALIDATION_ERROR} in the shared envelope rather than a body-parse failure.
 */
public record ProviderStatusRequest(@NotBlank(message = "status is required") String status) {
}
