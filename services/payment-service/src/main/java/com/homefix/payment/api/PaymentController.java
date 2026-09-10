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
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Initiate a payment; idempotent on (customerId, bookingId) (Requirement 12.3). */
    @PostMapping
    public ResponseEntity<TransactionResponse> initiate(@Valid @RequestBody InitiatePaymentRequest req) {
        PaymentTransaction tx = paymentService.initiatePayment(new InitiatePaymentCommand(
                req.customerId(), req.bookingId(), req.providerId(), req.amount(), req.platformFee(),
                req.method(), req.gatewayId(), req.paymentCredential()));
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(tx));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable UUID transactionId) {
        return TransactionResponse.from(paymentService.getTransaction(transactionId));
    }

    /** Handle a gateway callback; signature verified before any state change (Requirement 12.5). */
    @PostMapping("/callbacks/{transactionId}")
    public TransactionResponse callback(@PathVariable UUID transactionId,
                                        @Valid @RequestBody GatewayCallbackRequest req) {
        PaymentTransaction tx = paymentService.handleGatewayCallback(transactionId, new GatewayCallback(
                req.gatewayId(), req.payload(), req.signature(), req.succeeded(), req.failureReason()));
        return TransactionResponse.from(tx);
    }

    /** Record a customer-driven retry attempt (Requirement 12.8). */
    @PostMapping("/{transactionId}/retries")
    public TransactionResponse retry(@PathVariable UUID transactionId,
                                     @RequestParam(required = false) String failureReason) {
        return TransactionResponse.from(paymentService.retryPayment(transactionId, failureReason));
    }

    /** Refund a transaction fully or partially (Requirement 12.7). */
    @PostMapping("/{transactionId}/refunds")
    public TransactionResponse refund(@PathVariable UUID transactionId,
                                      @Valid @RequestBody RefundRequest req) {
        return TransactionResponse.from(paymentService.refund(transactionId, req.amount()));
    }

    /** Initiate a settlement bank transfer (Requirement 14.3). */
    @PostMapping("/settlements")
    public ResponseEntity<SettlementResponse> settle(@Valid @RequestBody SettlementRequest req) {
        Settlement settlement = paymentService.initiateSettlement(
                req.providerId(), req.amount(), req.bankAccountRef(), req.gatewayId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(settlement));
    }
}
