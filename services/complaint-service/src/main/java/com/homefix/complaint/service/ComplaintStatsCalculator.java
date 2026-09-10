package com.homefix.complaint.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;

/**
 * Pure aggregation of complaint statistics for Admin reports (Requirement 16.9): per-category
 * counts, resolution rate, and average resolution time. Stateless and side-effect free so it is
 * trivially unit-testable and reusable by both the scheduled refresh and the query endpoint.
 */
public class ComplaintStatsCalculator {

    /** Aggregates {@code complaints} into a {@link ComplaintStats} snapshot. */
    public ComplaintStats aggregate(List<Complaint> complaints) {
        Map<ComplaintCategory, Long> counts = new EnumMap<>(ComplaintCategory.class);
        for (ComplaintCategory category : ComplaintCategory.values()) {
            counts.put(category, 0L);
        }

        long total = 0;
        long resolved = 0;
        long resolutionMillisSum = 0;

        for (Complaint c : complaints) {
            total++;
            counts.merge(c.getCategory(), 1L, Long::sum);
            if (c.getStatus().isTerminal() && c.getResolvedAt() != null) {
                resolved++;
                resolutionMillisSum += Duration.between(c.getCreatedAt(), c.getResolvedAt()).toMillis();
            }
        }

        double resolutionRate = total == 0 ? 0.0 : (double) resolved / total;
        Duration averageResolutionTime = resolved == 0
                ? Duration.ZERO
                : Duration.ofMillis(resolutionMillisSum / resolved);

        return new ComplaintStats(counts, total, resolved, resolutionRate, averageResolutionTime);
    }
}
