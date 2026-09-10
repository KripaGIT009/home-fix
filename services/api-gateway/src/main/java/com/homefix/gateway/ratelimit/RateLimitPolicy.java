package com.homefix.gateway.ratelimit;

import java.util.Locale;

/**
 * Pure, side-effect-free resolution of the per-user rate limit for a request (Requirement 23.3,
 * Property 27).
 *
 * <p>The gateway enforces a fixed number of requests per one-minute window keyed on the
 * authenticated user. Limits differ per role:
 * <ul>
 *   <li>{@code CUSTOMER} — 100 requests / minute</li>
 *   <li>{@code PROVIDER} (a.k.a. {@code SERVICE_PROVIDER}) — 60 requests / minute</li>
 * </ul>
 *
 * <p>This class is intentionally free of any Spring, Redis, or servlet dependency so the limit and
 * key logic can be exercised directly by unit and property-based tests. The {@code windowSeconds}
 * is fixed at 60 so the {@code Retry-After} header (Requirement 23.5) can be derived deterministically.
 */
public final class RateLimitPolicy {

    /** Fixed rate-limit window, in seconds, for the per-user request limit. */
    public static final int WINDOW_SECONDS = 60;

    /** Requests-per-minute allowed for a CUSTOMER. */
    private final int customerLimit;

    /** Requests-per-minute allowed for a PROVIDER / SERVICE_PROVIDER. */
    private final int providerLimit;

    /** Fallback for any authenticated role not explicitly configured. */
    private final int defaultLimit;

    public RateLimitPolicy(int customerLimit, int providerLimit, int defaultLimit) {
        if (customerLimit <= 0 || providerLimit <= 0 || defaultLimit <= 0) {
            throw new IllegalArgumentException("rate limits must be positive");
        }
        this.customerLimit = customerLimit;
        this.providerLimit = providerLimit;
        this.defaultLimit = defaultLimit;
    }

    /** Convenience factory using the Requirement 23.3 defaults (CUSTOMER 100, PROVIDER 60). */
    public static RateLimitPolicy defaults() {
        return new RateLimitPolicy(100, 60, 60);
    }

    /**
     * Returns the maximum number of requests permitted in a single {@link #WINDOW_SECONDS} window
     * for the highest-privilege role among the caller's roles.
     *
     * <p>When a user holds multiple roles (Requirement 1.14 allows CUSTOMER + SERVICE_PROVIDER on
     * one account), the more generous CUSTOMER limit applies.
     */
    public int limitFor(Iterable<String> roles) {
        int limit = 0;
        boolean matched = false;
        if (roles != null) {
            for (String role : roles) {
                int candidate = limitForRole(role);
                if (candidate > 0) {
                    matched = true;
                    limit = Math.max(limit, candidate);
                }
            }
        }
        return matched ? limit : defaultLimit;
    }

    /** Returns the configured limit for a single role, or {@code 0} if the role is unknown/blank. */
    public int limitForRole(String role) {
        if (role == null || role.isBlank()) {
            return 0;
        }
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "CUSTOMER" -> customerLimit;
            case "PROVIDER", "SERVICE_PROVIDER" -> providerLimit;
            default -> 0;
        };
    }

    /**
     * Builds the Redis counter key for a user's per-minute window. Keyed on the subject (user id)
     * so limits are enforced per authenticated user, not per role, and never contain PII
     * (Requirement 26.4).
     */
    public String rateLimitKey(String subject) {
        String safeSubject = (subject == null || subject.isBlank()) ? "anonymous" : subject.trim();
        return "gw:ratelimit:user:" + safeSubject;
    }

    public int getCustomerLimit() {
        return customerLimit;
    }

    public int getProviderLimit() {
        return providerLimit;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }
}
