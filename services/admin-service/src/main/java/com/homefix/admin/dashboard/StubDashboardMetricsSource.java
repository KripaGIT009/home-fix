package com.homefix.admin.dashboard;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/**
 * Placeholder {@link DashboardMetricsSource} that returns zeroed figures. The cross-service HTTP
 * aggregation adapters are wired in a later integration task (Task 24 depends on services that
 * expose these figures); this stub keeps the Admin Service independently buildable and lets the
 * dashboard endpoint and its 60-second refresh be exercised end-to-end.
 */
@Component
public class StubDashboardMetricsSource implements DashboardMetricsSource {

    @Override
    public long activeBookings() {
        return 0L;
    }

    @Override
    public long activeProvidersOnline() {
        return 0L;
    }

    @Override
    public long newRegistrationsLast24h() {
        return 0L;
    }

    @Override
    public BigDecimal grossRevenueLast24h() {
        return BigDecimal.ZERO;
    }

    @Override
    public double avgProviderResponseTimeSeconds() {
        return 0.0d;
    }

    @Override
    public long openComplaintCount() {
        return 0L;
    }

    @Override
    public double platformRating() {
        return 0.0d;
    }
}
