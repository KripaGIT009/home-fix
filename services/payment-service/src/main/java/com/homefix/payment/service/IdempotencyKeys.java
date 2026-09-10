package com.homefix.payment.service;

import java.util.UUID;

/**
 * Derives the payment idempotency key scoped to {@code (customerId, bookingId)} (Requirement 12.3,
 * Property 11). The key is deterministic, so any two payment attempts for the same customer and
 * booking produce the same key and therefore collide on the unique key column / store entry.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String forCustomerBooking(UUID customerId, UUID bookingId) {
        return "cust:" + customerId + ":booking:" + bookingId;
    }
}
