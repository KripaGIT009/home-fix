package com.homefix.complaint.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drives the SLA-breach escalation sweep (Requirement 16.4) and refreshes the
 * complaint statistics used by Admin reports (Requirement 16.9). Timing intervals are configurable
 * so operations can tune the cadence without a code change; the stats interval stays within the
 * 60-minute freshness window (16.9).
 */
@Component
public class ComplaintSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(ComplaintSlaScheduler.class);

    private final ComplaintService complaintService;

    public ComplaintSlaScheduler(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    /** Runs the SLA-breach escalation sweep on a fixed cadence (Requirement 16.4). */
    @Scheduled(fixedDelayString = "${homefix.complaint.sla-sweep-interval-ms:60000}")
    public void sweepSlaBreaches() {
        try {
            complaintService.enforceSlaBreaches();
        } catch (RuntimeException e) {
            log.warn("SLA breach sweep failed; will retry on next tick");
        }
    }

    /**
     * Refreshes aggregated complaint statistics within the 60-minute freshness window
     * (Requirement 16.9). The aggregation itself is cheap and idempotent; this method exists so the
     * refresh cadence is observable and tunable independently of the query endpoint.
     */
    @Scheduled(fixedDelayString = "${homefix.complaint.stats-refresh-interval-ms:600000}")
    public void refreshStats() {
        try {
            ComplaintStats stats = complaintService.aggregateStats();
            log.debug("Refreshed complaint stats: total={} resolutionRate={}",
                    stats.totalComplaints(), stats.resolutionRate());
        } catch (RuntimeException e) {
            log.warn("Complaint stats refresh failed; will retry on next tick");
        }
    }
}
