package com.homefix.provider.api.dto;

import java.time.DayOfWeek;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /providers/{id}/availability} (Requirement 4.5).
 */
public record AvailabilityRequest(
        @Valid
        @NotNull(message = "slots is required")
        List<Slot> slots) {

    public record Slot(
            @NotNull(message = "dayOfWeek is required")
            DayOfWeek dayOfWeek,
            int startHour,
            int endHour) {
    }
}
