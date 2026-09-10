package com.homefix.reporting.domain;

/**
 * Pure decision logic for the synchronous-vs-asynchronous routing at the 7-day threshold
 * (Requirement 20.2, 20.3).
 *
 * <p>A range of 7 days or fewer (counting both endpoints) is served synchronously; a range
 * exceeding 7 days is served asynchronously. Kept free of Spring and I/O so the boundary can be
 * unit-tested directly.
 */
public final class GenerationModeSelector {

    /** Inclusive-day threshold at or below which a report is generated synchronously. */
    public static final long SYNCHRONOUS_THRESHOLD_DAYS = 7L;

    private GenerationModeSelector() {
    }

    /**
     * Selects the generation mode for the supplied filters based on the inclusive day count.
     *
     * @param filters the report filters carrying the date range
     * @return {@link GenerationMode#SYNCHRONOUS} when the range spans at most 7 days, otherwise
     *         {@link GenerationMode#ASYNCHRONOUS}
     */
    public static GenerationMode select(ReportFilters filters) {
        return filters.inclusiveDayCount() <= SYNCHRONOUS_THRESHOLD_DAYS
                ? GenerationMode.SYNCHRONOUS
                : GenerationMode.ASYNCHRONOUS;
    }
}
