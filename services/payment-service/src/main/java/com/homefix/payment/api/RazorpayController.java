package com.homefix.payment.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.payment.api.dto.RazorpayVerifyRequest;
import com.homefix.payment.api.dto.TransactionResponse;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.service.PaymentException;
import com.homefix.payment.service.PaymentService;
import com.homefix.payment.service.RazorpayPaymentService;
import com.homefix.payment.service.RazorpayPaymentService.RazorpayCheckout;

import jakarta.validation.Valid;

/**
 * Razorpay Checkout endpoints (Requirement 12.2, 12.5); see {@link RazorpayPaymentService}.
 *
 * <ul>
 *   <li>{@code GET /payments/{id}/razorpay/checkout}: what the customer app opens Checkout with,
 *       for its own PENDING Razorpay payment.</li>
 *   <li>{@code POST /payments/{id}/razorpay/verify}: the customer app forwards Checkout's success
 *       response.</li>
 *   <li>{@code POST /payments/webhooks/razorpay}: Razorpay's webhook. Public (no JWT): it is
 *       authenticated by the {@code X-Razorpay-Signature} HMAC over the raw body, which is why the
 *       body is taken as bytes rather than parsed.</li>
 * </ul>
 * The two customer endpoints check ownership like {@code POST /payments/{id}/retries}: the
 * transaction's customer, or staff.
 */
@RestController
@RequestMapping("/payments")
public class RazorpayController {

    private final PaymentService paymentService;
    private final RazorpayPaymentService razorpayPaymentService;
    private final CallerIdentity callerIdentity;

    public RazorpayController(PaymentService paymentService, RazorpayPaymentService razorpayPaymentService,
                              CallerIdentity callerIdentity) {
        this.paymentService = paymentService;
        this.razorpayPaymentService = razorpayPaymentService;
        this.callerIdentity = callerIdentity;
    }

    /** 409 {@code PAYMENT_NOT_AWAITING_CHECKOUT} once the payment is settled or is not a Razorpay one. */
    @GetMapping("/{transactionId}/razorpay/checkout")
    public RazorpayCheckout checkout(@PathVariable("transactionId") UUID transactionId) {
        PaymentTransaction tx = ownTransaction(transactionId);
        return razorpayPaymentService.checkoutFor(tx).orElseThrow(() -> new PaymentException(
                HttpStatus.CONFLICT, "PAYMENT_NOT_AWAITING_CHECKOUT",
                "Payment " + transactionId + " is not waiting to be paid in Razorpay Checkout"));
    }

    @PostMapping("/{transactionId}/razorpay/verify")
    public TransactionResponse verify(@PathVariable("transactionId") UUID transactionId,
                                      @Valid @RequestBody RazorpayVerifyRequest req) {
        PaymentTransaction tx = ownTransaction(transactionId);
        return TransactionResponse.from(razorpayPaymentService.confirmCheckout(
                tx, req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature()));
    }

    @PostMapping("/webhooks/razorpay")
    public Map<String, String> webhook(@RequestBody byte[] body,
                                       @RequestHeader(name = "X-Razorpay-Signature", required = false)
                                       String signature) {
        razorpayPaymentService.handleWebhook(body, signature);
        return Map.of("status", "ok");
    }

    private PaymentTransaction ownTransaction(UUID transactionId) {
        PaymentTransaction tx = paymentService.getTransaction(transactionId);
        callerIdentity.requireSelfOrStaff(tx.getCustomerId());
        return tx;
    }
}
