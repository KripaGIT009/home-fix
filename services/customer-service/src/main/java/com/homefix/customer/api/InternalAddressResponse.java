package com.homefix.customer.api;

import java.util.UUID;

import com.homefix.customer.domain.Address;

/**
 * Service-to-service view of a saved address, returned by
 * {@code GET /internal/addresses/{addressId}}.
 *
 * <p>Carries what a platform service needs to act on the address: its owner, its GPS coordinates
 * (Requirement 2.2 stores them even when reverse-geocoding fails, so they are always present) and
 * the label the customer gave it. The Dispatch Engine matches on coordinates alone; the Booking
 * Service passes the label to the booking's assigned provider, who needs to know where to go
 * (Requirement 11.1), and to nobody else. The encrypted street address (Requirement 2.7) is not
 * part of this projection.
 *
 * @param addressId  the address id
 * @param customerId the customer who owns the address, so a caller can confirm a booking points at
 *                   its own customer's address
 * @param lat        latitude in decimal degrees
 * @param lng        longitude in decimal degrees
 * @param label      the address as the customer entered it, e.g. "12 MG Road, Ara, 802301"; may
 *                   be null
 */
public record InternalAddressResponse(UUID addressId, UUID customerId, double lat, double lng,
                                      String label) {

    public static InternalAddressResponse from(Address address) {
        return new InternalAddressResponse(
                address.getId(), address.getCustomerId(), address.getLat(), address.getLng(),
                address.getLabel());
    }
}
