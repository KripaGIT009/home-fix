package com.homefix.payment.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.homefix.payment.booking.BookingClientPort;
import com.homefix.payment.booking.BookingPaymentFacts;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.gateway.PaymentGatewayRegistry;

/**
 * Pays for a completed booking (Requirement 12.2, 12.3, 12.10): the customer-facing
 * {@code POST /payments} flow.
 *
 * <p>The client names only the booking and the payment method. Who pays, who is credited and how
 * much all come from the Booking Service's payment facts, and the platform fee from the default
 * percentage rule, so a client can no longer pay a different amount, credit a different provider or
 * open a payment in another customer's name.
 *
 * <p>Order of steps, and why:
 * <ol>
 *   <li>Read the booking's facts. The caller must be the booking's customer, or staff paying on the
 *       customer's behalf; anybody else gets the same 404 as for a booking that does not exist.</li>
 *   <li>Return the live payment for (booking customer, booking) if there is one. This comes before
 *       the status check because after a successful payment the booking is PAYMENT_COMPLETED, and a
 *       duplicate request must still get its payment rather than a 409.</li>
 *   <li>Require a payable status (JOB_COMPLETED, CUSTOMER_CONFIRMED or PAYMENT_PENDING).</li>
 *   <li>Check the gateway exists, then move the booking to PAYMENT_PENDING, then charge through
 *       {@link PaymentService#initiatePayment}. The booking moves first so a charge is never taken on
 *       a booking that could not be marked as awaiting payment.</li>
 * </ol>
 * If the Booking Service cannot answer at any step the request fails with
 * {@code 503 BOOKING_SERVICE_UNAVAILABLE} before anything is charged.
 *
 * <p>The transaction's customer is always the booking's customer, never a staff caller's id, so
 * idempotency, ownership checks on later reads and the PaymentCompleted event all name the customer
 * who owes the money.
 */
@Service
public class BookingPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BookingPaymentService.class);

    private final BookingClientPort bookingClient;
    private final PaymentService paymentService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentProperties props;

    public BookingPaymentService(BookingClientPort bookingClient, PaymentService paymentService,
                                 PaymentGatewayRegistry gatewayRegistry, PaymentProperties props) {
        this.bookingClient = bookingClient;
        this.paymentService = paymentService;
        this.gatewayRegistry = gatewayRegistry;
        this.props = props;
    }

    /**
     * Pays for {@code cmd.bookingId()}.
     *
     * @param callerId the authenticated caller (JWT subject); may be {@code null} when {@code staff}
     * @param staff    whether the caller holds a staff role and may pay on the customer's behalf
     * @return the payment: the existing live one, or the newly initiated attempt
     * @throws PaymentException 404 {@code BOOKING_NOT_FOUND}, 409 {@code BOOKING_NOT_PAYABLE}, 503
     *                          {@code BOOKING_SERVICE_UNAVAILABLE}, 400 for an unknown gateway, plus
     *                          everything {@link PaymentService#initiatePayment} can raise
     */
    public PaymentTransaction payForBooking(PayBookingCommand cmd, UUID callerId, boolean staff) {
        if (cmd.bookingId() == null) {
            throw PaymentException.validation("bookingId is required");
        }
        if (cmd.method() == null) {
            throw PaymentException.validation("Payment method is required");
        }
        UUID bookingId = cmd.bookingId();

        BookingPaymentFacts facts = bookingClient.paymentFacts(bookingId)
                .orElseThrow(() -> PaymentException.bookingNotFound(bookingId));
        if (!staff && (callerId == null || !callerId.equals(facts.customerId()))) {
            log.info("Caller {} tried to pay booking {}, which is not theirs; answering 404", callerId, bookingId);
            throw PaymentException.bookingNotFound(bookingId);
        }
        UUID customerId = facts.customerId();

        Optional<PaymentTransaction> existing = paymentService.findActivePayment(customerId, bookingId);
        if (existing.isPresent()) {
            log.info("Booking {} already has payment {} ({}); returning it",
                    bookingId, existing.get().getId(), existing.get().getStatus());
            return existing.get();
        }

        if (!facts.isPayable()) {
            log.info("Booking {} is {}; not payable", bookingId, facts.status());
            throw PaymentException.bookingNotPayable(bookingId);
        }
        requirePriced(facts);

        String gatewayId = cmd.gatewayId() == null || cmd.gatewayId().isBlank()
                ? props.getDefaultGateway()
                : cmd.gatewayId();
        // Fail an unknown gateway before the booking is moved on.
        gatewayRegistry.require(gatewayId);

        BookingPaymentFacts pending = bookingClient.markPaymentPending(bookingId, customerId);
        // The facts as of the move are the ones charged; they cannot name another customer.
        if (!customerId.equals(pending.customerId())) {
            throw PaymentException.bookingNotFound(bookingId);
        }
        requirePriced(pending);

        return paymentService.initiatePayment(new InitiatePaymentCommand(
                customerId, bookingId, pending.providerId(), pending.amount(),
                null, // platform fee from the default-percentage rule (Requirement 12.10)
                cmd.method(), gatewayId, cmd.rawPaymentCredential()));
    }

    /** A booking without a provider or a positive amount cannot be charged. */
    private static void requirePriced(BookingPaymentFacts facts) {
        BigDecimal amount = facts.amount();
        if (facts.providerId() == null || amount == null || amount.signum() <= 0) {
            log.warn("Booking {} has no provider or no positive amount (provider {}, amount {}); not payable",
                    facts.bookingId(), facts.providerId(), amount);
            throw PaymentException.bookingNotPayable(facts.bookingId());
        }
    }
}
