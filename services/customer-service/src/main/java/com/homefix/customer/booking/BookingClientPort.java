package com.homefix.customer.booking;

import java.util.Optional;
import java.util.UUID;

/**
 * Hexagonal port abstracting the outbound call to the Booking Service used to decide
 * whether an address may be deleted (Requirement 2.6).
 *
 * <p>Per the design's per-service database isolation rule, the Customer Service never
 * reads Booking tables directly — it asks the Booking Service via an API call. This port
 * hides that transport so the deletion logic is testable with a deterministic fake.
 */
public interface BookingClientPort {

    /**
     * Finds an active booking that references the given address, if any.
     *
     * @param customerId the owning customer
     * @param addressId  the address being considered for deletion
     * @return the reference/id of a blocking active booking, or {@link Optional#empty()}
     *         if no active booking uses the address
     */
    Optional<String> findActiveBookingUsingAddress(UUID customerId, UUID addressId);
}
