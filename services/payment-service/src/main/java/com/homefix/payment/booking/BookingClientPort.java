package com.homefix.payment.booking;

import java.util.Optional;
import java.util.UUID;

/**
 * The Payment Service's view of the Booking Service (Requirement 12.2): the facts a booking payment
 * is priced from, and the move of the booking into {@code PAYMENT_PENDING} before it is charged.
 * Both calls go to the Booking Service's internal endpoints with the shared service credential.
 *
 * <p>Failures are reported as {@code PaymentException}s the REST layer surfaces directly:
 * {@code 503 BOOKING_SERVICE_UNAVAILABLE} when the Booking Service cannot answer, so nothing is
 * charged on a booking whose state could not be checked.
 */
public interface BookingClientPort {

    /**
     * Reads a booking's payment facts.
     *
     * @return the facts, or empty when the Booking Service does not know the booking
     * @throws com.homefix.payment.service.PaymentException 503 {@code BOOKING_SERVICE_UNAVAILABLE}
     */
    Optional<BookingPaymentFacts> paymentFacts(UUID bookingId);

    /**
     * Moves the booking to {@code PAYMENT_PENDING} on behalf of its customer (through
     * {@code CUSTOMER_CONFIRMED} when it is still {@code JOB_COMPLETED}). Idempotent on the Booking
     * Service side: a booking already {@code PAYMENT_PENDING} is answered unchanged.
     *
     * @param customerId the booking's own customer (never a staff caller's id)
     * @return the booking's facts after the move
     * @throws com.homefix.payment.service.PaymentException 404 {@code BOOKING_NOT_FOUND}, 409
     *                                                      {@code BOOKING_NOT_PAYABLE}, 503
     *                                                      {@code BOOKING_SERVICE_UNAVAILABLE}
     */
    BookingPaymentFacts markPaymentPending(UUID bookingId, UUID customerId);
}
