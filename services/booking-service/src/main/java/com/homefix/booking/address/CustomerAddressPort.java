package com.homefix.booking.address;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the saved address a booking points at. The Customer Service owns addresses; a booking
 * stores only the id, so the provider's job screen — which must show where to go (Requirement
 * 11.1) — needs this lookup.
 */
public interface CustomerAddressPort {

    /** @return the address, or empty when it is unknown, deleted, or cannot be fetched right now */
    Optional<ServiceAddress> find(UUID addressId);

    /**
     * A saved address as the job screen shows it.
     *
     * @param label the address as the customer entered it (street, city, PIN), may be null
     */
    record ServiceAddress(String label, double latitude, double longitude) {
    }
}
