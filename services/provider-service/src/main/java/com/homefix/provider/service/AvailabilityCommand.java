package com.homefix.provider.service;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Validated intent to replace a provider's general availability schedule (Requirement 4.5).
 */
public record AvailabilityCommand(List<Slot> slots) {

    /** A single 1-hour-granularity slot: {@code [startHour, endHour)} on {@code dayOfWeek}. */
    public record Slot(DayOfWeek dayOfWeek, int startHour, int endHour) {
    }
}
