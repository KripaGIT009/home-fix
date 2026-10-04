package com.homefix.dispatch.domain;

/**
 * The Provider Service could not be asked for eligible providers: a timeout, a 5xx, an open circuit
 * breaker, or a refused service credential (401/403, a deployment error) (Requirements 8.2, 24.4).
 *
 * <p>Deliberately distinct from an empty result. The eligible-provider query used to degrade to an
 * empty list on any failure, so an outage — or a mismatched {@code INTERNAL_API_KEY}, which refuses
 * every call — looked exactly like an empty market: every radius came back empty and every booking
 * was failed as "no provider available". Nothing is known about the market when this is thrown, so
 * the dispatch loop must not conclude that nobody is available.
 */
public class ProviderSearchUnavailableException extends RuntimeException {

    public ProviderSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
