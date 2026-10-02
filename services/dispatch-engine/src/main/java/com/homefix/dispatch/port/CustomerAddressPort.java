package com.homefix.dispatch.port;

import java.util.UUID;

/**
 * Outbound port to the Customer Service for resolving a booking's address to the coordinates a
 * provider search is centred on (Requirement 8.2).
 *
 * <p>{@code BookingCreated} carries the booking's {@code addressId}, not its coordinates: the
 * Customer Service owns where a customer lives, and the Booking Service publishes booking facts.
 * The Dispatch Engine therefore resolves the address itself, just before matching, through this
 * port. Per the per-service database isolation rule it never reads customer tables directly.
 */
public interface CustomerAddressPort {

    /**
     * Resolves an address by id.
     *
     * @param addressId the address the booking references
     * @return the address's owner and coordinates (never {@code null})
     * @throws com.homefix.dispatch.domain.UnresolvableBookingException if the Customer Service
     *         confirms no such address exists; retrying cannot help
     * @throws com.homefix.dispatch.domain.EnrichmentUnavailableException if the Customer Service
     *         could not be consulted (timeout, 5xx, open breaker, refused credential); retrying may
     *         help
     */
    ResolvedAddress lookup(UUID addressId);

    /**
     * An address as the Dispatch Engine needs it.
     *
     * @param addressId  the address id
     * @param customerId the customer who owns the address
     * @param lat        latitude in decimal degrees
     * @param lng        longitude in decimal degrees
     */
    record ResolvedAddress(UUID addressId, UUID customerId, double lat, double lng) {
    }
}
