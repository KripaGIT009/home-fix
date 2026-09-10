package com.homefix.customer.api;

/**
 * Request body for {@code POST /customers/{id}/location/detect} (Requirement 2.5).
 *
 * <p>{@code gpsDenied} lets the device signal that the user refused GPS permission; in
 * that case (or when coordinates are absent) the service returns an error prompting manual
 * entry.
 */
public record LocationDetectionRequest(
        Double lat,
        Double lng,
        boolean gpsDenied) {
}
