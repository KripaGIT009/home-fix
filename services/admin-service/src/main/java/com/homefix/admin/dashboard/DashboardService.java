package com.homefix.admin.dashboard;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains the dashboard metric snapshot, refreshed every 60 seconds (Requirement 19.1).
 *
 * <p>A single {@link DashboardMetrics} snapshot is held in memory and recomputed on a fixed
 * cadence by {@link #refresh()} (driven by {@code @Scheduled}). Admin requests read the current
 * snapshot in O(1) without fanning out to downstream services on every call, keeping the
 * dashboard responsive while staying within the 60-second freshness bound.
 *
 * <p>The snapshot is computed once eagerly at construction so the endpoint returns real data even
 * before the first scheduled tick.
 */
@Service
public class DashboardService {

    /** 60-second refresh cadence in milliseconds (Requirement 19.1). */
    static final long REFRESH_INTERVAL_MILLIS = 60_000L;

    private final DashboardMetricsSource source;
    private final Clock clock;
    private final AtomicReference<DashboardMetrics> snapshot = new AtomicReference<>();

    public DashboardService(DashboardMetricsSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
        this.snapshot.set(compute());
    }

    /** Returns the most recently computed metric snapshot. */
    public DashboardMetrics currentMetrics() {
        return snapshot.get();
    }

    /** Recomputes the snapshot from the metrics source; invoked every 60 seconds. */
    @Scheduled(fixedRateString = "${homefix.admin.dashboard-refresh-interval:PT60S}")
    public void refresh() {
        snapshot.set(compute());
    }

    private DashboardMetrics compute() {
        return new DashboardMetrics(
                source.activeBookings(),
                source.activeProvidersOnline(),
                source.newRegistrationsLast24h(),
                source.grossRevenueLast24h(),
                source.avgProviderResponseTimeSeconds(),
                source.openComplaintCount(),
                source.platformRating(),
                clock.instant());
    }
}
