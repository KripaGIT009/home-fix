package com.homefix.payment.service;

import java.util.UUID;

/**
 * Derives the payment idempotency key scoped to {@code (customerId, bookingId)} (Requirement 12.3,
 * Property 11). The key is deterministic, so any two payment attempts for the same customer and
 * booking produce the same key and therefore collide on the unique key column / store entry.
 *
 * <p>Refund keys (Requirement 12.7) are scoped to the transaction and built from a client-supplied
 * key instead: two separate refunds of the same amount are legitimate, so the server cannot tell a
 * retry from a new refund on its own; the client's key is what identifies "the same request".
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String forCustomerBooking(UUID customerId, UUID bookingId) {
        return "cust:" + customerId + ":booking:" + bookingId;
    }

    /** Maximum length of a client-supplied refund idempotency key (keeps the derived key within 128). */
    public static final int MAX_CLIENT_REFUND_KEY_LENGTH = 64;

    public static String forRefund(UUID transactionId, String clientKey) {
        return "refund:" + transactionId + ":" + clientKey;
    }
}
