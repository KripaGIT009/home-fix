package com.homefix.complaint.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.service.ComplaintStats;

/** Response projection of aggregated complaint statistics for Admin reports (Requirement 16.9). */
public record ComplaintStatsResponse(
        Map<String, Long> categoryCounts,
        long totalComplaints,
        long resolvedComplaints,
        double resolutionRate,
        long averageResolutionSeconds) {

    public static ComplaintStatsResponse from(ComplaintStats stats) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<ComplaintCategory, Long> e : stats.categoryCounts().entrySet()) {
            counts.put(e.getKey().name(), e.getValue());
        }
        return new ComplaintStatsResponse(
                counts,
                stats.totalComplaints(),
                stats.resolvedComplaints(),
                stats.resolutionRate(),
                stats.averageResolutionTime().getSeconds());
    }
}
