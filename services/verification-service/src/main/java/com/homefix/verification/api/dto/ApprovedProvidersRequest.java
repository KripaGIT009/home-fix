package com.homefix.verification.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /internal/verifications/approved}: the provider ids to check.
 *
 * <p>Capped at {@value #MAX_PROVIDER_IDS} ids per request so one call cannot turn into an
 * unbounded {@code IN} list; the Provider Service sends larger candidate sets in chunks.
 */
public record ApprovedProvidersRequest(
        @NotNull(message = "providerIds is required")
        @Size(max = MAX_PROVIDER_IDS, message = "at most " + MAX_PROVIDER_IDS + " providerIds per request")
        List<@NotNull(message = "providerIds must not contain null") UUID> providerIds) {

    public static final int MAX_PROVIDER_IDS = 500;
}
