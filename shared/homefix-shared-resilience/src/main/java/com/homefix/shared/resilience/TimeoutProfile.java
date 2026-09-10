package com.homefix.shared.resilience;

import java.time.Duration;

/**
 * Outbound-call timeout profiles mandated by Requirement 24.3.
 *
 * <ul>
 *   <li>{@link #CRITICAL_PATH} — calls in the booking or dispatch request path are capped at
 *       5 seconds so a slow dependency cannot blow the emergency-dispatch latency budget.</li>
 *   <li>{@link #STANDARD} — all other outbound calls are capped at 15 seconds.</li>
 * </ul>
 *
 * <p>The profile drives both the transport-level connect/read timeout on the HTTP client and the
 * Resilience4j {@code TimeLimiter} wrapping the call, so a hung socket is still bounded.
 */
public enum TimeoutProfile {

    /** Booking / dispatch critical path — 5 second ceiling (Requirement 24.3). */
    CRITICAL_PATH(Duration.ofSeconds(5)),

    /** All other outbound calls — 15 second ceiling (Requirement 24.3). */
    STANDARD(Duration.ofSeconds(15));

    private final Duration timeout;

    TimeoutProfile(Duration timeout) {
        this.timeout = timeout;
    }

    /** The maximum time an outbound call under this profile may take. */
    public Duration timeout() {
        return timeout;
    }
}
