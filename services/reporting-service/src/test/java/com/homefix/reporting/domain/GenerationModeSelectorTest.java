package com.homefix.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the synchronous-vs-asynchronous routing at the 7-day threshold (Requirement 20.2,
 * 20.3), including the exact boundary.
 */
class GenerationModeSelectorTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    private static ReportFilters range(int inclusiveDays) {
        // inclusiveDays counts both endpoints, so add (days - 1) to the start.
        return new ReportFilters(START, START.plusDays(inclusiveDays - 1L), null, null, null);
    }

    @Test
    void singleDayRangeIsSynchronous() {
        assertThat(range(1).inclusiveDayCount()).isEqualTo(1);
        assertThat(GenerationModeSelector.select(range(1))).isEqualTo(GenerationMode.SYNCHRONOUS);
    }

    @Test
    void sevenDayRangeIsSynchronous() {
        assertThat(range(7).inclusiveDayCount()).isEqualTo(7);
        assertThat(GenerationModeSelector.select(range(7))).isEqualTo(GenerationMode.SYNCHRONOUS);
    }

    @Test
    void eightDayRangeIsAsynchronous() {
        assertThat(range(8).inclusiveDayCount()).isEqualTo(8);
        assertThat(GenerationModeSelector.select(range(8))).isEqualTo(GenerationMode.ASYNCHRONOUS);
    }

    @Test
    void longRangeIsAsynchronous() {
        assertThat(GenerationModeSelector.select(range(365)))
                .isEqualTo(GenerationMode.ASYNCHRONOUS);
    }
}
