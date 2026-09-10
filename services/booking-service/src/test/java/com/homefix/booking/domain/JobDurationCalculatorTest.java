package com.homefix.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Example-based tests for the net job duration calculation (Requirement 11.6, Property 10):
 * net = Σ WORK intervals − Σ PAUSE intervals. The dedicated property-based test is a separate
 * task; these cover representative examples and edge cases.
 */
class JobDurationCalculatorTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2024-01-01T09:00:00Z");

    private static JobInterval work(long startSec, long endSec) {
        JobInterval i = JobInterval.work(BOOKING, T0.plusSeconds(startSec));
        i.close(T0.plusSeconds(endSec));
        return i;
    }

    private static JobInterval pause(long startSec, long endSec) {
        JobInterval i = JobInterval.pause(BOOKING, T0.plusSeconds(startSec), "break");
        i.close(T0.plusSeconds(endSec));
        return i;
    }

    @Test
    void singleWorkIntervalNoPause() {
        // 30 minutes of work, no pause.
        List<JobInterval> intervals = List.of(work(0, 1800));
        long net = JobDurationCalculator.netDurationSeconds(intervals, T0.plusSeconds(1800));
        assertThat(net).isEqualTo(1800);
    }

    @Test
    void multiplePauseIntervalsSubtractFromWork() {
        // WORK 0-600 (10m), PAUSE 600-900 (5m), WORK 900-1500 (10m), PAUSE 1500-1560 (1m),
        // WORK 1560-2160 (10m). Work = 30m = 1800s; Pause = 6m = 360s; net = 1440s.
        List<JobInterval> intervals = List.of(
                work(0, 600),
                pause(600, 900),
                work(900, 1500),
                pause(1500, 1560),
                work(1560, 2160));
        long net = JobDurationCalculator.netDurationSeconds(intervals, T0.plusSeconds(2160));
        assertThat(net).isEqualTo(1440);
        // Sanity: equals total work minus total pause.
        assertThat(net).isEqualTo(Duration.ofMinutes(30).getSeconds() - Duration.ofMinutes(6).getSeconds());
    }

    @Test
    void openWorkIntervalIsClosedAtCompletionInstant() {
        // Final WORK interval left open; completedAt closes it.
        JobInterval open = JobInterval.work(BOOKING, T0.plusSeconds(1000));
        List<JobInterval> intervals = List.of(work(0, 600), pause(600, 900), open);
        Instant completedAt = T0.plusSeconds(1900); // open interval => 900s
        long net = JobDurationCalculator.netDurationSeconds(intervals, completedAt);
        // work: 600 + 900 = 1500; pause: 300; net = 1200
        assertThat(net).isEqualTo(1200);
    }

    @Test
    void neverNegativeWhenPauseExceedsWork() {
        List<JobInterval> intervals = List.of(work(0, 100), pause(100, 1000));
        long net = JobDurationCalculator.netDurationSeconds(intervals, T0.plusSeconds(1000));
        assertThat(net).isZero();
    }

    @Test
    void emptyIntervalsYieldZero() {
        assertThat(JobDurationCalculator.netDurationSeconds(List.of(), T0)).isZero();
    }
}
