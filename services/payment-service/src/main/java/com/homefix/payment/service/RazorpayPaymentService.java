package com.homefix.payment.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.gateway.RazorpayGatewayAdapter.RazorpayPayment;

/**
 * Completes payments taken through Razorpay Checkout (Requirement 12.2, 12.5).
 *
 * <p>{@code POST /payments} with the {@code razorpay} gateway opens a PENDING transaction whose
 * gateway reference is a Razorpay order for the booking's amount. The customer then pays that order
 * in Checkout, and the payment is confirmed by whichever arrives first:
 * <ul>
 *   <li>{@link #confirmCheckout}: the browser forwards what Checkout returned. The signature must
 *       verify, the order must be this transaction's, and the payment is read back from Razorpay
 *       (captured if it is only authorized) so its amount and status come from Razorpay rather than
 *       the browser.</li>
 *   <li>{@link #handleWebhook}: Razorpay's {@code payment.captured} / {@code order.paid} webhook,
 *       signed over the raw body with the webhook secret. It covers the customer who paid and closed
 *       the tab before the browser could confirm.</li>
 * </ul>
 * Both settle through {@link PaymentService#settleVerifiedOutcome}, so the amount binding, replay
 * handling, PaymentCompleted event, invoice and provider wallet credit are the ones every gateway
 * uses; the second confirmation of the same payment is a no-op.
 *
 * <p>A failed attempt inside Checkout does not fail the transaction: Checkout lets the customer try
 * again against the same order, and a later success must still settle it. A customer who gives up
 * simply leaves it PENDING; paying again reopens Checkout on the same order.
 */
@Service
public class RazorpayPaymentService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentService paymentService;
    private final RazorpayGatewayAdapter razorpay;

    public RazorpayPaymentService(PaymentService paymentService, RazorpayGatewayAdapter razorpay) {
        this.paymentService = paymentService;
        this.razorpay = razorpay;
    }

    /**
     * What the browser needs to open Checkout for {@code tx}: present only while the transaction is a
     * PENDING Razorpay payment with its order created.
     */
    public Optional<RazorpayCheckout> checkoutFor(PaymentTransaction tx) {
        if (!RazorpayGatewayAdapter.GATEWAY_ID.equals(tx.getGateway())
                || tx.getStatus() != TransactionStatus.PENDING
                || tx.getGatewayReference() == null
                || !razorpay.isReady()) {
            return Optional.empty();
        }
        return Optional.of(new RazorpayCheckout(
                razorpay.keyId(),
                tx.getGatewayReference(),
                RazorpayGatewayAdapter.toPaise(tx.getAmount()),
                RazorpayGatewayAdapter.CURRENCY,
                tx.getBookingReference()));
    }

    /**
     * Confirms a payment Checkout reported as successful. The caller has already checked that the
     * requester may act on {@code tx}.
     *
     * @return the transaction: SUCCESS once confirmed, unchanged if it was already settled, or still
     *         PENDING if Razorpay has not captured the payment yet (its webhook settles it later)
     * @throws PaymentException 400 {@code INVALID_SIGNATURE} or {@code CALLBACK_MISMATCH} for a
     *                          confirmation that does not belong to this transaction; 502
     *                          {@code PAYMENT_GATEWAY_ERROR} if Razorpay could not be asked
     */
    public PaymentTransaction confirmCheckout(PaymentTransaction tx, String orderId, String paymentId,
                                              String signature) {
        requireRazorpay(tx);
        if (tx.getStatus() != TransactionStatus.PENDING) {
            log.info("Checkout confirmation for transaction {} which is already {}; nothing to do",
                    tx.getId(), tx.getStatus());
            return tx;
        }
        if (orderId == null || !orderId.equals(tx.getGatewayReference())) {
            log.warn("SECURITY checkout confirmation for transaction {} names order {}, not its order {}",
                    tx.getId(), orderId, tx.getGatewayReference());
            throw PaymentException.callbackMismatch("This payment is not for this transaction");
        }
        if (!razorpay.verifyCheckoutSignature(orderId, paymentId, signature)) {
            log.warn("SECURITY invalid Razorpay checkout signature for transaction {} order {}",
                    tx.getId(), orderId);
            throw PaymentException.invalidSignature("Invalid payment signature for transaction " + tx.getId());
        }

        RazorpayPayment payment = readCaptured(paymentId, tx);
        if (!payment.isCaptured()) {
            log.info("Razorpay payment {} for transaction {} is {}; leaving it PENDING for the webhook",
                    paymentId, tx.getId(), payment.status());
            return tx;
        }
        return paymentService.settleVerifiedOutcome(tx.getId(), captured(tx.getId(), payment, Instant.now()));
    }

    /**
     * Handles a Razorpay webhook delivery. Only the raw body is trusted, and only once its signature
     * verifies; events for orders this service did not create are acknowledged and ignored, so they
     * are not re-delivered.
     *
     * @throws PaymentException 400 for a bad signature or an unreadable body (Razorpay re-delivers),
     *                          and whatever settling the payment raises
     */
    public void handleWebhook(byte[] body, String signature) {
        String payload = new String(body, StandardCharsets.UTF_8);
        if (!razorpay.verifyCallbackSignature(payload, signature)) {
            log.warn("SECURITY invalid Razorpay webhook signature");
            throw PaymentException.invalidSignature("Invalid webhook signature");
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            throw PaymentException.invalidCallbackPayload("Webhook body is not valid JSON");
        }
        String type = event.path("event").asText("");
        switch (type) {
            case "payment.captured", "order.paid" -> settleFromWebhook(type, event);
            case "payment.failed" -> log.info("Razorpay payment {} failed for order {}: {}; the transaction "
                            + "stays PENDING while the customer can still pay the order",
                    event.at("/payload/payment/entity/id").asText(),
                    event.at("/payload/payment/entity/order_id").asText(),
                    event.at("/payload/payment/entity/error_description").asText());
            default -> log.debug("Ignoring Razorpay webhook event {}", type);
        }
    }

    private void settleFromWebhook(String type, JsonNode event) {
        RazorpayPayment payment;
        try {
            payment = RazorpayPayment.from(event.at("/payload/payment/entity"));
        } catch (IllegalStateException e) {
            throw PaymentException.invalidCallbackPayload("Webhook " + type + " carries no payment");
        }
        if (!payment.isCaptured()) {
            log.info("Razorpay {} for payment {} which is {}; nothing to settle", type, payment.id(),
                    payment.status());
            return;
        }
        Optional<PaymentTransaction> tx = Optional.ofNullable(payment.orderId())
                .flatMap(paymentService::findByGatewayReference)
                .filter(found -> RazorpayGatewayAdapter.GATEWAY_ID.equals(found.getGateway()));
        if (tx.isEmpty()) {
            log.warn("Razorpay {} for order {} (payment {}) matches no transaction; ignored",
                    type, payment.orderId(), payment.id());
            return;
        }
        requireRupees(payment, tx.get());
        long createdAt = event.path("created_at").asLong(0);
        Instant at = createdAt > 0 ? Instant.ofEpochSecond(createdAt) : Instant.now();
        paymentService.settleVerifiedOutcome(tx.get().getId(), captured(tx.get().getId(), payment, at));
    }

    /**
     * Reads the payment back from Razorpay, checks it is this transaction's order and amount, and
     * captures it if the account left it only authorized.
     */
    private RazorpayPayment readCaptured(String paymentId, PaymentTransaction tx) {
        RazorpayPayment payment;
        try {
            payment = razorpay.fetchPayment(paymentId);
        } catch (RuntimeException e) {
            throw gatewayUnreachable(tx, e);
        }
        if (!tx.getGatewayReference().equals(payment.orderId())
                || payment.amountPaise() != RazorpayGatewayAdapter.toPaise(tx.getAmount())) {
            log.warn("SECURITY Razorpay payment {} is for order {} / {} paise, but transaction {} is order {} / {}",
                    paymentId, payment.orderId(), payment.amountPaise(), tx.getId(), tx.getGatewayReference(),
                    tx.getAmount());
            throw PaymentException.callbackMismatch("This payment does not match the transaction");
        }
        requireRupees(payment, tx);
        if (!payment.isAuthorized()) {
            return payment;
        }
        try {
            return razorpay.capture(paymentId, payment.amountPaise());
        } catch (HttpClientErrorException e) {
            // Usually a race with Razorpay's automatic capture; read where it stands now.
            log.info("Capturing Razorpay payment {} was refused ({}); re-reading it", paymentId, e.getStatusCode());
            try {
                return razorpay.fetchPayment(paymentId);
            } catch (RuntimeException readFailure) {
                throw gatewayUnreachable(tx, readFailure);
            }
        } catch (RuntimeException e) {
            throw gatewayUnreachable(tx, e);
        }
    }

    private static void requireRupees(RazorpayPayment payment, PaymentTransaction tx) {
        if (!RazorpayGatewayAdapter.CURRENCY.equals(payment.currency())) {
            log.warn("SECURITY Razorpay payment {} is in {}, transaction {} is in {}",
                    payment.id(), payment.currency(), tx.getId(), RazorpayGatewayAdapter.CURRENCY);
            throw PaymentException.callbackMismatch("This payment is in the wrong currency");
        }
    }

    /** A captured payment as the outcome every gateway settles through; its id is the event id. */
    private static SignedCallbackPayload captured(UUID transactionId, RazorpayPayment payment, Instant at) {
        BigDecimal amount = RazorpayGatewayAdapter.fromPaise(payment.amountPaise());
        return new SignedCallbackPayload(payment.id(), transactionId, RazorpayGatewayAdapter.GATEWAY_ID,
                SignedCallbackPayload.Outcome.SUCCEEDED, amount, null, at);
    }

    private static void requireRazorpay(PaymentTransaction tx) {
        if (!RazorpayGatewayAdapter.GATEWAY_ID.equals(tx.getGateway())) {
            throw PaymentException.validation("Transaction " + tx.getId() + " is not a Razorpay payment");
        }
    }

    private static PaymentException gatewayUnreachable(PaymentTransaction tx, RuntimeException e) {
        log.error("Could not confirm Razorpay payment for transaction {} with Razorpay: {}", tx.getId(), e.getMessage());
        return new PaymentException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR",
                "We could not confirm the payment with Razorpay yet; it will update on its own shortly");
    }

    /**
     * What Razorpay Checkout is opened with.
     *
     * @param keyId       the public key id
     * @param orderId     the Razorpay order to pay
     * @param amount      the order amount in paise
     * @param currency    always INR
     * @param description the booking reference, shown in Checkout
     */
    public record RazorpayCheckout(String keyId, String orderId, long amount, String currency,
                                   String description) {
    }
}
