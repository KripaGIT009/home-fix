package com.homefix.complaint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;

/**
 * Unit tests for {@link ComplaintStatsCalculator} — the Admin-report aggregation (Requirement 16.9):
 * per-category counts, resolution rate, and average resolution time.
 */
class ComplaintStatsCalculatorTest {

    private static final Instant CREATED = Instant.parse("2024-06-01T12:00:00Z");

    private final ComplaintStatsCalculator calculator = new ComplaintStatsCalculator();

    private Complaint complaint(ComplaintCategory category) {
        return Complaint.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), category, ServicePriority.STANDARD, "d", CREATED,
                CREATED.plus(Duration.ofHours(72)));
    }

    @Test
    void emptyInputYieldsZeroedStats() {
        ComplaintStats stats = calculator.aggregate(List.of());

        assertThat(stats.totalComplaints()).isZero();
        assertThat(stats.resolvedComplaints()).isZero();
        assertThat(stats.resolutionRate()).isZero();
        assertThat(stats.averageResolutionTime()).isEqualTo(Duration.ZERO);
        // Every category is present with a zero count.
        assertThat(stats.categoryCounts()).hasSize(ComplaintCategory.values().length);
        assertThat(stats.categoryCounts().values()).allMatch(v -> v == 0L);
    }

    @Test
    void countsCategoriesAndComputesResolutionRateAndAverageTime() {
        Complaint resolved1 = complaint(ComplaintCategory.POOR_QUALITY);
        resolved1.resolve(CREATED.plus(Duration.ofHours(2)));
        Complaint resolved2 = complaint(ComplaintCategory.POOR_QUALITY);
        resolved2.close(CREATED.plus(Duration.ofHours(4)));
        Complaint open = complaint(ComplaintCategory.DAMAGE);

        ComplaintStats stats = calculator.aggregate(List.of(resolved1, resolved2, open));

        assertThat(stats.totalComplaints()).isEqualTo(3);
        assertThat(stats.resolvedComplaints()).isEqualTo(2);
        assertThat(stats.categoryCounts().get(ComplaintCategory.POOR_QUALITY)).isEqualTo(2);
        assertThat(stats.categoryCounts().get(ComplaintCategory.DAMAGE)).isEqualTo(1);
        assertThat(stats.resolutionRate()).isCloseTo(2.0 / 3.0, within(1e-9));
        // Average of 2h and 4h = 3h.
        assertThat(stats.averageResolutionTime()).isEqualTo(Duration.ofHours(3));
    }
}
