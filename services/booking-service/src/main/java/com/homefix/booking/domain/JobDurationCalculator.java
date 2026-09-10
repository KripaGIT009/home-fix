package com.homefix.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Pure, stateless calculator for the net job duration (Requirement 11.6, Property 10).
 *
 * <p>Given the ordered sequence of {@link JobInterval}s for a booking, the net duration is the
 * sum of all {@link JobInterval.Kind#WORK} interval durations minus the sum of all
 * {@link JobInterval.Kind#PAUSE} interval durations. Any interval still open at completion is
 * closed at the completion instant before being summed.
 *
 * <p>Kept free of Spring and persistence so it is trivially unit-testable and reusable by the
 * dedicated property-based test task.
 */
public final class JobDurationCalculator {

    private JobDurationCalculator() {
    }

    /**
     * Computes net working duration in seconds.
     *
     * @param intervals the booking's intervals (order-independent; each must have a start)
     * @param completedAt instant used to close any interval still open (typically the
     *        JOB_COMPLETED timestamp)
     * @return {@code Σ WORK − Σ PAUSE} in whole seconds; never negative (floored at 0)
     */
    public static long netDurationSeconds(List<JobInterval> intervals, Instant completedAt) {
        long workSeconds = 0L;
        long pauseSeconds = 0L;
        for (JobInterval interval : intervals) {
            Instant end = interval.getEndedAt() != null ? interval.getEndedAt() : completedAt;
            long seconds = Duration.between(interval.getStartedAt(), end).getSeconds();
            if (interval.getKind() == JobInterval.Kind.WORK) {
                workSeconds += seconds;
            } else {
                pauseSeconds += seconds;
            }
        }
        long net = workSeconds - pauseSeconds;
        return Math.max(net, 0L);
    }
}
