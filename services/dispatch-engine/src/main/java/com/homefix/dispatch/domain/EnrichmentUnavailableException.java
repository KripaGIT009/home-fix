package com.homefix.dispatch.domain;

/**
 * A dependency the Dispatch Engine needs to complete a booking's matching problem (the Customer
 * Service for the address, the Service Catalog for the skill tags) could not be consulted: a
 * timeout, a 5xx, an open circuit breaker, or a refused service credential (Requirements 8.2,
 * 24.4).
 *
 * <p>Recoverable by definition: nothing is known to be wrong with the booking itself. The
 * {@code BookingCreated} consumer lets it propagate so the shared consumer's retry and
 * dead-letter path handles it, and a dead-lettered record can be replayed once the dependency is
 * back.
 */
public class EnrichmentUnavailableException extends RuntimeException {

    public EnrichmentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
