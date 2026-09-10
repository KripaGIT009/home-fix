package com.homefix.admin.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The seven real-time dashboard metrics refreshed every 60 seconds (Requirement 19.1):
 *
 * <ol>
 *   <li>{@code activeBookings} — total active Bookings.</li>
 *   <li>{@code activeProvidersOnline} — total active Providers online.</li>
 *   <li>{@code newRegistrationsLast24h} — new Customer registrations in the last 24 hours.</li>
 *   <li>{@code grossRevenueLast24h} — gross revenue in the last 24 hours.</li>
 *   <li>{@code avgProviderResponseTimeSeconds} — average provider response time over the last
 *       24 hours.</li>
 *   <li>{@code openComplaintCount} — open complaint count.</li>
 *   <li>{@code platformRating} — overall platform rating (mean of all Review ratings, 1.0–5.0).</li>
 * </ol>
 *
 * <p>{@code refreshedAt} records when this snapshot was computed so the client can display data
 * freshness.
 */
public record DashboardMetrics(
        long activeBookings,
        long activeProvidersOnline,
        long newRegistrationsLast24h,
        BigDecimal grossRevenueLast24h,
        double avgProviderResponseTimeSeconds,
        long openComplaintCount,
        double platformRating,
        Instant refreshedAt) {
}
