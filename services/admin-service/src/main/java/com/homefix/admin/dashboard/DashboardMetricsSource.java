package com.homefix.admin.dashboard;

import java.math.BigDecimal;

/**
 * Port for gathering the raw dashboard figures from the owning services (Booking, Provider,
 * Customer/Auth, Payment, Complaint, Rating). The Admin Service never reads other services'
 * tables directly (per-service DB isolation); a production adapter aggregates these over the
 * respective service APIs, while tests supply a deterministic fake.
 *
 * <p>Each method returns a single figure for the corresponding {@link DashboardMetrics} field.
 */
public interface DashboardMetricsSource {

    long activeBookings();

    long activeProvidersOnline();

    long newRegistrationsLast24h();

    BigDecimal grossRevenueLast24h();

    double avgProviderResponseTimeSeconds();

    long openComplaintCount();

    /** Overall platform rating on a 1.0–5.0 scale (mean of all Review ratings). */
    double platformRating();
}
