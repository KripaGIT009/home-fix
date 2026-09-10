package com.homefix.reporting.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The filter criteria for a report request (Requirement 20.4): an inclusive date range plus the
 * optional Service_Category, geographic region, and Provider dimensions.
 *
 * <p>The date range is central to the synchronous-vs-asynchronous routing decision (Requirement
 * 20.2, 20.3): {@link #inclusiveDayCount()} counts both endpoints, so a request whose {@code from}
 * and {@code to} are the same day spans one day.
 *
 * <p>Optional dimensions are held as nullable fields and exposed via {@link Optional} accessors so
 * callers never dereference a null. No PII is stored here (Requirement 26.4): the provider is
 * referenced by opaque ID, not name.
 */
public record ReportFilters(
        LocalDate from,
        LocalDate to,
        String serviceCategory,
        String region,
        String providerId) {

    public ReportFilters {
        Objects.requireNonNull(from, "from date is required");
        Objects.requireNonNull(to, "to date is required");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("report 'to' date must not precede 'from' date");
        }
    }

    /**
     * Number of days covered by the range, counting both endpoints. A single-day range
     * ({@code from == to}) returns 1.
     */
    public long inclusiveDayCount() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public Optional<String> serviceCategoryFilter() {
        return Optional.ofNullable(serviceCategory);
    }

    public Optional<String> regionFilter() {
        return Optional.ofNullable(region);
    }

    public Optional<String> providerIdFilter() {
        return Optional.ofNullable(providerId);
    }
}
