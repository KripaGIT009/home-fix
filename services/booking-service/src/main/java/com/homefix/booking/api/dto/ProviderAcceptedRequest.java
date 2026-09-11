package com.homefix.booking.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /internal/bookings/{bookingId}/provider-accepted}.
 *
 * @param providerId the provider who accepted the offer, chosen by the Dispatch Engine
 */
public record ProviderAcceptedRequest(

        @NotNull(message = "providerId is required")
        UUID providerId) {
}
