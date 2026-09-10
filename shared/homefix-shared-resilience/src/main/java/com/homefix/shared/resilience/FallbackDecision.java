package com.homefix.shared.resilience;

/**
 * Supplies the degraded response a caller wants returned when a resilient call cannot complete —
 * either because the circuit breaker is open or because every retry attempt failed
 * (Requirement 24.4).
 *
 * @param <T> the type the guarded call returns
 */
@FunctionalInterface
public interface FallbackDecision<T> {

    /**
     * Produces the degraded fallback value for the given failure.
     *
     * @param cause the failure that triggered the fallback (a
     *              {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException} when the
     *              breaker is open, otherwise the last downstream exception)
     * @return the degraded response to hand back to the caller
     */
    T apply(Throwable cause);
}
