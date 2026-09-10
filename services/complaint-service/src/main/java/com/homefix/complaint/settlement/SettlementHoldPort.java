package com.homefix.complaint.settlement;

import java.util.UUID;

/**
 * Coordinates provider settlement holds with the Booking Service (Requirement 16.7, 16.8). When a
 * Support_Agent sets a booking to DISPUTED, the associated provider settlement is held until the
 * complaint is closed; on resolution or closure the hold is released for amounts not subject to a
 * refund. Modelled as a port so the transport (synchronous HTTP, Kafka command, etc.) can vary and
 * so it is mockable in unit tests.
 */
public interface SettlementHoldPort {

    /** Places a hold on the provider settlement for a disputed booking (Requirement 16.7). */
    void placeHold(UUID bookingId, UUID providerId, UUID complaintId);

    /** Releases a previously-placed settlement hold on complaint closure (Requirement 16.8). */
    void releaseHold(UUID bookingId, UUID providerId, UUID complaintId);
}
