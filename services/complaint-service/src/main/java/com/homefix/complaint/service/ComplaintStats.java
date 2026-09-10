package com.homefix.complaint.service;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import com.homefix.complaint.domain.ComplaintCategory;

/**
 * Aggregated complaint statistics for Admin reports (Requirement 16.9): per-category counts, the
 * overall resolution rate, and the average resolution time across resolved/closed complaints.
 *
 * @param categoryCounts       count of complaints per {@link ComplaintCategory}
 * @param totalComplaints      total number of complaints
 * @param resolvedComplaints   number of complaints in a terminal (RESOLVED/CLOSED) state
 * @param resolutionRate       resolved / total, in [0.0, 1.0]; 0.0 when there are no complaints
 * @param averageResolutionTime mean time from creation to resolution across resolved complaints;
 *                              {@link Duration#ZERO} when none are resolved
 */
public record ComplaintStats(
        Map<ComplaintCategory, Long> categoryCounts,
        long totalComplaints,
        long resolvedComplaints,
        double resolutionRate,
        Duration averageResolutionTime) {

    public ComplaintStats {
        categoryCounts = categoryCounts == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(categoryCounts);
    }
}
