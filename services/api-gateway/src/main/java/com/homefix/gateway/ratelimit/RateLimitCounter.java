package com.homefix.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fixed-window counter used by the rate-limit and OTP filters. Each call increments the counter for
 * {@code key} and returns the running count within the current window; the window's TTL is set on
 * first increment so the count resets automatically (Requirement 23.3, 23.4).
 *
 * <p>Abstracted behind an interface so the reactive-Redis implementation used in production can be
 * swapped for a deterministic in-memory counter in tests (no Redis required to prove Property 27).
 */
public interface RateLimitCounter {

    /**
     * Increments the counter for {@code key} and returns the new count. On the first increment of a
     * fresh window the key is created with the given {@code window} TTL.
     */
    Mono<Long> incrementAndGet(String key, Duration window);
}
