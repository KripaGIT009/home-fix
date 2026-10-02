package com.homefix.verification.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the internal suspend / reinstate actions the Provider Service drives on behalf
 * of an Admin ({@code PATCH /admin/providers/{id}/status}, Requirement 19.2).
 *
 * <p>The caller is a service, so the acting Admin cannot be read from a principal here; it is
 * passed explicitly and is required, because every status change must record who made it
 * (Requirement 5.11). The internal credential is what makes that claim trustworthy.
 */
public record InternalStatusChangeRequest(
        @NotNull(message = "actorId is required") UUID actorId,
        @Size(max = 1000, message = "reason must be at most 1000 characters") String reason) {
}
