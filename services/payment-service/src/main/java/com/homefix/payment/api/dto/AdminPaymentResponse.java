package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;

/**
 * One row of the Admin Portal's payment list, {@code GET /admin/payments}, and the result of its
 * refund (Requirement 19.2) — the portal's {@code AdminPayment} type, field for field. Never
 * exposes the stored encrypted credential (Requirement 12.9).
 *
 * <h2>Translated values</h2>
 * <ul>
 *   <li>{@code status} and {@code method} use the portal's vocabulary, which differs from the
 *       domain enums in a few names (see {@link #status} and {@link #method}). Both mappings are
 *       exhaustive switches, so a new domain value fails the build instead of reaching the portal
 *       as an unknown string.</li>
 *   <li>{@code refundedAmount} is the transaction's running refunded total. The refund flow adds to
 *       it in the same database transaction that marks a refund SUCCEEDED, and never for a PENDING
 *       or FAILED one, so it equals the sum of the succeeded refunds without a second query.</li>
 *   <li>{@code currency} is always {@code INR}: amounts are stored without a currency because the
 *       platform charges in rupees only.</li>
 * </ul>
 *
 * <h2>Fields this service does not own</h2>
 * {@code bookingReference} carries the booking's <em>id</em>: the human-readable reference lives in
 * the Booking Service, which this service has no client for, but the id is a real booking key —
 * the Admin Portal's booking search and {@code GET /bookings/{key}} both accept it — so the row
 * still identifies its booking. {@code customerName} lives in the Auth Service and is sent as
 * {@code null}; the portal treats it as optional.
 */
public record AdminPaymentResponse(
        UUID id,
        String bookingReference,
        String customerName,
        BigDecimal amount,
        BigDecimal refundedAmount,
        String currency,
        String method,
        String status,
        String gateway,
        Instant createdAt) {

    /** The only currency the platform charges in. */
    public static final String CURRENCY = "INR";

    public static AdminPaymentResponse from(PaymentTransaction tx) {
        return new AdminPaymentResponse(
                tx.getId(),
                tx.getBookingId().toString(),
                null,
                tx.getAmount(),
                tx.getRefundedAmount(),
                CURRENCY,
                method(tx.getMethod()),
                status(tx.getStatus()),
                tx.getGateway(),
                tx.getCreatedAt());
    }

    /** The portal calls a settled payment {@code COMPLETED}; the domain calls it {@code SUCCESS}. */
    static String status(TransactionStatus status) {
        return switch (status) {
            case PENDING -> "PENDING";
            case SUCCESS -> "COMPLETED";
            case FAILED -> "FAILED";
            case REFUNDED -> "REFUNDED";
            case PARTIALLY_REFUNDED -> "PARTIALLY_REFUNDED";
        };
    }

    /** The portal's shorter method names. */
    static String method(PaymentMethod method) {
        return switch (method) {
            case UPI -> "UPI";
            case CREDIT_DEBIT_CARD -> "CARD";
            case NET_BANKING -> "NETBANKING";
            case WALLET -> "WALLET";
            case CASH -> "CASH";
        };
    }
}
