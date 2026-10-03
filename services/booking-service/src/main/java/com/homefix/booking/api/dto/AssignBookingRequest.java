package com.homefix.booking.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /tenant/bookings/{key}/assignment}: the Tenant's Provider to assign
 * (Requirement MT-5.2).
 */
public record AssignBookingRequest(@NotNull UUID providerId) {
}
