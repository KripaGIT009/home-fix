package com.homefix.customer.service;

import java.util.UUID;

/**
 * Outcome of saving an address. When {@code geocoded} is false the raw coordinates were
 * stored and {@code warning} explains that the street address could not be resolved
 * automatically (Requirement 2.2).
 */
public record AddressResult(
        UUID addressId,
        double lat,
        double lng,
        boolean geocoded,
        boolean isDefault,
        String warning) {
}
