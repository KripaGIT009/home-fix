package com.homefix.payment.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.payment.api.dto.GatewayCallbackRequest;
import com.homefix.payment.api.dto.InitiatePaymentRequest;
import com.homefix.payment.api.dto.RefundRequest;
import com.homefix.payment.api.dto.SettlementRequest;
import com.homefix.payment.api.dto.SettlementResponse;
import com.homefix.payment.api.dto.TransactionResponse;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.Settlement;
import com.homefix.payment.service.GatewayCallback;
import com.homefix.payment.service.InitiatePaymentCommand;
import com.homefix.payment.service.PaymentService;

import jakarta.validation.Valid;

/**
 * REST surface for the Payment Service (Requirement 12, 14.3-14.4).
 *
 * <p>Gateway callbacks live under {@code /payments/callbacks/**}, which is permitted without JWT
 * auth in {@code WebSecurityConfig}; they are authenticated instead by cryptographic signature
 * verification in {@link PaymentService#handleGatewayCallback} (Requirement 12.5).
 *
 * <p>Role-based access is enforced by {@code PaymentRbacConfig}; on top of it, the customer-facing
 * endpoints assert <em>ownership</em> via {@link CallerIdentity} so a customer cannot read another
 * customer's transaction, open a payment in somebody else's name, or push another customer's
 * PENDING payment towards FAILED through the retry endpoint. The callback, refund, reconcile and
 * settlement handlers carry no ownership check: the callback has no authenticated principal at all
 * (it is HMAC-verified), and refunds/reconciliation/settlements are staff-only by role.
 *
 * <p>{@code @PathVariable}/{@code @RequestParam} names are spelled out explicitly because the build
 * does not enable the {@code -parameters} compiler flag, and Spring 6 no longer falls back to the
 * local-variable table to infer them.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final CallerIdentity callerIdentity;

    public PaymentController(PaymentService paymentService, CallerIdentity callerIdentity) {
        this.paymentService = paymentService;
        this.callerIdentity = callerIdentity;
    }

    /** Initiate a payment; idempotent on (customerId, bookingId) (Requirement 12.3). */
    @PostMapping
    public ResponseEntity<TransactionResponse> initiate(@Valid @RequestBody InitiatePaymentRequest req) {
        // A customer may only open a payment in their own name; staff may act for anybody.
        callerIdentity.requireSelfOrStaff(req.customerId());
        PaymentTransaction tx = paymentService.initiatePayment(new InitiatePaymentCommand(
                req.customerId(), req.bookingId(), req.providerId(), req.amount(), req.platformFee(),
                req.method(), req.gatewayId(), req.paymentCredential()));
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(tx));
    }

    /** Read a single transaction; restricted to the owning customer or to staff. */
    @org.springframework.web.bind.annotation.GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable("transactionId") UUID transactionId) {
        PaymentTransaction tx = paymentService.getTransaction(transactionId);
        callerIdentity.requireSelfOrStaff(tx.getCustomerId());
        return TransactionResponse.from(tx);
    }

    /**
     * Handle a gateway callback (Requirement 12.5). Only the signature-verified {@code payload} is
     * passed on as the source of the outcome; see {@link GatewayCallbackRequest}.
     */
    @PostMapping("/callbacks/{transactionId}")
    public TransactionResponse callback(@PathVariable("transactionId") UUID transactionId,
                                        @Valid @RequestBody GatewayCallbackRequest req) {
        PaymentTransaction tx = paymentService.handleGatewayCallback(transactionId, new GatewayCallback(
                req.gatewayId(), req.payload(), req.signature()));
        return TransactionResponse.from(tx);
    }

    /**
     * Record a customer-driven retry attempt (Requirement 12.8); restricted to the owning customer or
     * to staff. Each attempt counts towards the limit that marks the payment permanently FAILED, so
     * without the ownership check any customer could fail somebody else's payment.
     */
    @PostMapping("/{transactionId}/retries")
    public TransactionResponse retry(@PathVariable("transactionId") UUID transactionId,
                                     @RequestParam(name = "failureReason", required = false) String failureReason) {
        callerIdentity.requireSelfOrStaff(paymentService.getTransaction(transactionId).getCustomerId());
        return TransactionResponse.from(paymentService.retryPayment(transactionId, failureReason));
    }

    /** Refund a transaction fully or partially; idempotent per client key (Requirement 12.7). */
    @PostMapping("/{transactionId}/refunds")
    public TransactionResponse refund(@PathVariable("transactionId") UUID transactionId,
                                      @Valid @RequestBody RefundRequest req) {
        return TransactionResponse.from(
                paymentService.refund(transactionId, req.amount(), req.idempotencyKey()));
    }

    /**
     * Settle a refund stuck in PENDING by re-sending it to the gateway under its original refund id
     * and recording the outcome (Requirement 12.7); staff-only (FINANCE tier) by role.
     */
    @PostMapping("/{transactionId}/refunds/{refundId}/reconcile")
    public TransactionResponse reconcileRefund(@PathVariable("transactionId") UUID transactionId,
                                               @PathVariable("refundId") UUID refundId) {
        return TransactionResponse.from(paymentService.reconcileRefund(transactionId, refundId));
    }

    /** Initiate a settlement bank transfer (Requirement 14.3). */
    @PostMapping("/settlements")
    public ResponseEntity<SettlementResponse> settle(@Valid @RequestBody SettlementRequest req) {
        Settlement settlement = paymentService.initiateSettlement(
                req.providerId(), req.amount(), req.bankAccountRef(), req.gatewayId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(settlement));
    }
}
