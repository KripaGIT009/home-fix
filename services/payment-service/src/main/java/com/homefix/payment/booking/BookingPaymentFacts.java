package com.homefix.payment.booking;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * What the Booking Service says a booking costs and who it is between, from its internal
 * {@code GET /internal/bookings/{id}/payment-facts} (Requirement 12.2, 12.10). A payment is priced
 * from these facts only; the customer, provider and amount a client sends are never trusted.
 *
 * @param bookingId  the booking
 * @param reference  the human-readable booking reference
 * @param customerId the booking's customer, who is the paying customer even when staff pay for them
 * @param providerId the provider who did the job and is credited on success
 * @param status     the booking status, as the Booking Service names it
 * @param amount     final total, or the estimate when no final total exists (two decimals)
 * @param currency   ISO currency code; always {@code INR} today
 */
public record BookingPaymentFacts(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID providerId,
        String status,
        BigDecimal amount,
        String currency) {

    /** Booking statuses from which a payment may be started (the job is done, nothing paid yet). */
    public static final Set<String> PAYABLE_STATUSES =
            Set.of("JOB_COMPLETED", "CUSTOMER_CONFIRMED", "PAYMENT_PENDING");

    /** @return whether the booking is in a state a payment may be started from. */
    public boolean isPayable() {
        return status != null && PAYABLE_STATUSES.contains(status);
    }
}
