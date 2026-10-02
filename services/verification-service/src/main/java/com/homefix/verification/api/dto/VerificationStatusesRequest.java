package com.homefix.verification.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /internal/verifications/statuses}: the provider ids whose current
 * verification status the caller wants.
 *
 * <p>Capped at {@value #MAX_PROVIDER_IDS} ids per request, the same cap as the {@code /approved}
 * lookup, so one call cannot turn into an unbounded {@code IN} list.
 */
public record VerificationStatusesRequest(
        @NotNull(message = "providerIds is required")
        @Size(max = MAX_PROVIDER_IDS, message = "at most " + MAX_PROVIDER_IDS + " providerIds per request")
        List<@NotNull(message = "providerIds must not contain null") UUID> providerIds) {

    public static final int MAX_PROVIDER_IDS = ApprovedProvidersRequest.MAX_PROVIDER_IDS;
}
