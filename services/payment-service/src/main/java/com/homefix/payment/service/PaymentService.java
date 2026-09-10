package com.homefix.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.payment.alert.FinanceAlertPort;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.KmsEncryptionPort;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.PaymentTransactionRepository;
import com.homefix.payment.domain.Settlement;
import com.homefix.payment.domain.SettlementRepository;
import com.homefix.payment.domain.SettlementStatus;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.event.PaymentCompletedPublisher;
import com.homefix.payment.gateway.GatewayChargeRequest;
import com.homefix.payment.gateway.GatewayChargeResult;
import com.homefix.payment.gateway.GatewayRefundRequest;
import com.homefix.payment.gateway.GatewayRefundResult;
import com.homefix.payment.gateway.GatewayTransferRequest;
import com.homefix.payment.gateway.GatewayTransferResult;
import com.homefix.payment.gateway.PaymentGatewayPort;
import com.homefix.payment.gateway.PaymentGatewayRegistry;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.idempotency.IdempotencyStorePort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.wallet.ProviderWalletClientPort;

/**
 * Core payment-domain business logic (Requirement 12 and 14.3-14.4).
 *
 * <p>Responsibilities: idempotent payment initiation scoped to {@code (customerId, bookingId)}
 * (12.3), gateway selection through the {@link PaymentGatewayRegistry} abstraction (12.1),
 * cryptographic callback signature verification and state-machine-guarded state changes (12.4,
 * 12.5), publishing {@code PaymentCompleted} via the outbox plus invoice triggering with retry
 * (12.6), provider wallet credit with retry-then-alert (12.10, 12.11), customer retry attempts
 * (12.8), refunds (12.7), and settlement bank transfers (14.3, 14.4).
 *
 * <p>All external dependencies are expressed as ports so the logic is fully unit-testable against
 * in-memory fakes and mocks. All monetary math uses {@link BigDecimal}.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PaymentTransactionRepository transactionRepository;
    private final SettlementRepository settlementRepository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final IdempotencyStorePort idempotencyStore;
    private final KmsEncryptionPort kms;
    private final ProviderWalletClientPort walletClient;
    private final InvoiceTriggerPort invoiceTrigger;
    private final PaymentCompletedPublisher paymentCompletedPublisher;
    private final FinanceAlertPort financeAlert;
    private final ProviderNotificationPort providerNotification;
    private final PaymentProperties props;

    public PaymentService(PaymentTransactionRepository transactionRepository,
                          SettlementRepository settlementRepository,
                          PaymentGatewayRegistry gatewayRegistry,
                          IdempotencyStorePort idempotencyStore,
                          KmsEncryptionPort kms,
                          ProviderWalletClientPort walletClient,
                          InvoiceTriggerPort invoiceTrigger,
                          PaymentCompletedPublisher paymentCompletedPublisher,
                          FinanceAlertPort financeAlert,
                          ProviderNotificationPort providerNotification,
                          PaymentProperties props) {
        this.transactionRepository = transactionRepository;
        this.settlementRepository = settlementRepository;
        this.gatewayRegistry = gatewayRegistry;
        this.idempotencyStore = idempotencyStore;
        this.kms = kms;
        this.walletClient = walletClient;
        this.invoiceTrigger = invoiceTrigger;
        this.paymentCompletedPublisher = paymentCompletedPublisher;
        this.financeAlert = financeAlert;
        this.providerNotification = providerNotification;
        this.props = props;
    }

    // ===================== Initiate (Requirement 12.2, 12.3, 12.9) =====================

    /**
     * Initiates a payment. Idempotent on {@code (customerId, bookingId)}: a duplicate request
     * returns the original transaction without a new gateway charge (Requirement 12.3, Property 11).
     */
    @Transactional
    public PaymentTransaction initiatePayment(InitiatePaymentCommand cmd) {
        validateInitiate(cmd);
        String key = IdempotencyKeys.forCustomerBooking(cmd.customerId(), cmd.bookingId());

        // First line of defence: an existing record for this key returns the original status.
        Optional<PaymentTransaction> existing = transactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("Idempotent payment: returning existing transaction {} for key {}",
                    existing.get().getId(), key);
            return existing.get();
        }

        PaymentGatewayPort gateway = gatewayRegistry.require(cmd.gatewayId());
        BigDecimal platformFee = resolvePlatformFee(cmd.amount(), cmd.platformFee());

        // Requirement 12.9: never store a raw card number; store only an encrypted credential token.
        String encryptedCredential = cmd.rawPaymentCredential() == null
                ? null
                : kms.encrypt(cmd.rawPaymentCredential());

        PaymentTransaction tx = PaymentTransaction.initiate(key, cmd.customerId(), cmd.bookingId(),
                cmd.providerId(), cmd.amount(), platformFee, cmd.method(), gateway.gatewayId(),
                encryptedCredential);

        // Reserve the idempotency key before charging so concurrent duplicates short-circuit.
        boolean reserved = idempotencyStore.putIfAbsent(key, tx.getId(), props.getIdempotencyTtl());
        if (!reserved) {
            Optional<UUID> priorId = idempotencyStore.find(key);
            Optional<PaymentTransaction> prior = priorId.flatMap(transactionRepository::findById)
                    .or(() -> transactionRepository.findByIdempotencyKey(key));
            if (prior.isPresent()) {
                log.info("Idempotent payment (store hit): returning existing transaction {} for key {}",
                        prior.get().getId(), key);
                return prior.get();
            }
        }

        GatewayChargeResult result = gateway.charge(new GatewayChargeRequest(
                cmd.bookingId(), cmd.customerId(), cmd.amount(), cmd.method()));
        tx.setGatewayReference(result.gatewayReference());
        return transactionRepository.save(tx);
    }

    private void validateInitiate(InitiatePaymentCommand cmd) {
        if (cmd.customerId() == null || cmd.bookingId() == null || cmd.providerId() == null) {
            throw PaymentException.validation("customerId, bookingId, and providerId are required");
        }
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            throw PaymentException.validation("Payment amount must be positive");
        }
        if (cmd.method() == null) {
            throw PaymentException.validation("Payment method is required");
        }
        if (cmd.gatewayId() == null || cmd.gatewayId().isBlank()) {
            throw PaymentException.validation("Gateway id is required");
        }
    }

    /** Resolves the platform fee amount: explicit if provided, else derived from the default %. */
    private BigDecimal resolvePlatformFee(BigDecimal amount, BigDecimal explicitFee) {
        if (explicitFee != null) {
            if (explicitFee.signum() < 0) {
                throw PaymentException.validation("Platform fee cannot be negative");
            }
            if (explicitFee.compareTo(amount) > 0) {
                throw PaymentException.validation("Platform fee cannot exceed the payment amount");
            }
            return explicitFee;
        }
        return amount.multiply(props.getDefaultPlatformFeePercent())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    // ===================== Gateway callback (Requirement 12.5, 12.6, 12.10) =====================

    /**
     * Handles an asynchronous gateway callback: verifies the signature (Requirement 12.5), then
     * transitions the transaction to SUCCESS or FAILED (Requirement 12.4). On SUCCESS it publishes
     * PaymentCompleted, triggers invoice generation, and credits the provider wallet.
     *
     * @throws PaymentException 400 if the signature is invalid (the callback is rejected and logged).
     */
    @Transactional
    public PaymentTransaction handleGatewayCallback(UUID transactionId, GatewayCallback callback) {
        PaymentTransaction tx = getExisting(transactionId);
        PaymentGatewayPort gateway = gatewayRegistry.require(callback.gatewayId());

        // Requirement 12.5: verify the cryptographic signature BEFORE any state change.
        if (!gateway.verifyCallbackSignature(callback.payload(), callback.signature())) {
            log.warn("SECURITY invalid gateway callback signature for transaction {} gateway {}",
                    transactionId, callback.gatewayId());
            throw PaymentException.invalidSignature(
                    "Invalid callback signature for transaction " + transactionId);
        }

        if (callback.succeeded()) {
            tx.transitionTo(TransactionStatus.SUCCESS);
            transactionRepository.save(tx);
            onPaymentSuccess(tx);
        } else {
            tx.recordFailureReason(callback.failureReason());
            tx.transitionTo(TransactionStatus.FAILED);
            transactionRepository.save(tx);
        }
        return tx;
    }

    /**
     * Post-SUCCESS side effects (Requirement 12.6, 12.10, 12.11): publish PaymentCompleted through
     * the outbox (atomic with the state change), trigger invoice generation with retry, and credit
     * the provider wallet with retry-then-alert.
     */
    private void onPaymentSuccess(PaymentTransaction tx) {
        // Atomic with the SUCCESS state change: the outbox row commits in this same transaction.
        paymentCompletedPublisher.publish(tx);

        // Requirement 12.6: trigger invoice generation; retry up to N with exponential backoff.
        Retries.Result invoiceResult = Retries.run(props.getMaxInvoiceRetries(), props.getRetryBackoff(),
                () -> invoiceTrigger.triggerInvoiceGeneration(tx.getId(), tx.getBookingId()));
        if (!invoiceResult.succeeded()) {
            log.error("CRITICAL invoice trigger failed after {} attempts for payment {} booking {}",
                    invoiceResult.attempts(), tx.getId(), tx.getBookingId());
        }

        // Requirement 12.10/12.11: credit provider net earnings; retry then alert Finance_Admin.
        BigDecimal net = tx.providerNetEarning();
        Retries.Result walletResult = Retries.run(props.getMaxWalletCreditRetries(), props.getRetryBackoff(),
                () -> walletClient.creditEarning(tx.getProviderId(), tx.getBookingId(),
                        tx.getAmount(), tx.getPlatformFee(), net));
        if (!walletResult.succeeded()) {
            String reason = walletResult.lastError() == null ? "unknown"
                    : walletResult.lastError().getMessage();
            log.error("Wallet credit failed after {} attempts for provider {} booking {} amount {}",
                    walletResult.attempts(), tx.getProviderId(), tx.getBookingId(), net);
            financeAlert.walletCreditFailed(tx.getProviderId(), tx.getBookingId(), net, reason);
        }
    }

    // ===================== Customer retry (Requirement 12.8) =====================

    /**
     * Records a customer-driven retry of a failed/pending payment. Once the configured maximum
     * number of attempts is reached, the transaction is marked permanently FAILED (Requirement 12.8).
     *
     * @return the transaction after registering the attempt.
     */
    @Transactional
    public PaymentTransaction retryPayment(UUID transactionId, String failureReason) {
        PaymentTransaction tx = getExisting(transactionId);
        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new PaymentException(HttpStatus.CONFLICT, "NOT_RETRYABLE",
                    "Only a PENDING transaction can be retried; current state " + tx.getStatus());
        }
        tx.recordFailureReason(failureReason);
        int attempts = tx.registerRetryAttempt();
        if (attempts >= props.getMaxCustomerRetries()) {
            tx.transitionTo(TransactionStatus.FAILED);
            log.info("Transaction {} permanently FAILED after {} attempts", transactionId, attempts);
        }
        return transactionRepository.save(tx);
    }

    // ===================== Refund (Requirement 12.7) =====================

    /**
     * Initiates a refund via the gateway and updates the transaction to REFUNDED or
     * PARTIALLY_REFUNDED (Requirement 12.7). If the gateway refund call fails, the state is left
     * unchanged, the failure is logged, and the Finance_Admin team is alerted for manual processing.
     */
    @Transactional
    public PaymentTransaction refund(UUID transactionId, BigDecimal refundAmount) {
        PaymentTransaction tx = getExisting(transactionId);
        if (tx.getStatus() != TransactionStatus.SUCCESS
                && tx.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            throw PaymentException.invalidTransition(
                    "Only a SUCCESS or PARTIALLY_REFUNDED transaction can be refunded; current state "
                            + tx.getStatus());
        }
        PaymentGatewayPort gateway = gatewayRegistry.require(tx.getGateway());

        GatewayRefundResult result;
        try {
            result = gateway.refund(new GatewayRefundRequest(tx.getGatewayReference(), refundAmount));
        } catch (RuntimeException e) {
            log.error("Gateway refund call threw for transaction {}: {}", transactionId, e.getMessage());
            financeAlert.refundFailed(tx.getId(), tx.getBookingId(), refundAmount, e.getMessage());
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR",
                    "Gateway refund failed for transaction " + transactionId);
        }

        if (!result.succeeded()) {
            log.error("Gateway refund failed for transaction {}", transactionId);
            financeAlert.refundFailed(tx.getId(), tx.getBookingId(), refundAmount, "gateway rejected refund");
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR",
                    "Gateway refund failed for transaction " + transactionId);
        }

        tx.applyRefund(refundAmount);
        return transactionRepository.save(tx);
    }

    // ===================== Settlement bank transfer (Requirement 14.3, 14.4) =====================

    /**
     * Initiates a settlement bank transfer for a provider (Requirement 14.3): creates the record in
     * PENDING, advances to PROCESSING, and initiates the transfer via the gateway. On success the
     * settlement moves to COMPLETED; on failure it moves to FAILED, the amount is credited back to
     * the provider wallet, and both the provider and Finance_Admin are notified (Requirement 14.4).
     */
    @Transactional
    public Settlement initiateSettlement(UUID providerId, BigDecimal amount, String bankAccountRef,
                                         String gatewayId) {
        if (amount == null || amount.signum() <= 0) {
            throw PaymentException.validation("Settlement amount must be positive");
        }
        if (bankAccountRef == null || bankAccountRef.isBlank()) {
            throw PaymentException.validation("A destination bank account reference is required");
        }
        PaymentGatewayPort gateway = gatewayRegistry.require(gatewayId);

        // Requirement 12.9/4.9: store the bank account reference encrypted at rest.
        Settlement settlement = Settlement.initiate(providerId, amount, kms.encrypt(bankAccountRef));
        settlementRepository.save(settlement);

        settlement.transitionTo(SettlementStatus.PROCESSING);

        GatewayTransferResult result;
        try {
            result = gateway.transfer(new GatewayTransferRequest(providerId, amount, bankAccountRef));
        } catch (RuntimeException e) {
            return failSettlement(settlement, providerId, amount, e.getMessage());
        }

        if (result.succeeded()) {
            settlement.setGatewayReference(result.gatewayTransferReference());
            settlement.transitionTo(SettlementStatus.COMPLETED);
            return settlementRepository.save(settlement);
        }
        return failSettlement(settlement, providerId, amount, "gateway rejected transfer");
    }

    private Settlement failSettlement(Settlement settlement, UUID providerId, BigDecimal amount,
                                      String reason) {
        settlement.recordFailure(reason);
        settlement.transitionTo(SettlementStatus.FAILED);
        settlementRepository.save(settlement);
        // Requirement 14.4: credit the amount back to the wallet, notify provider + Finance_Admin.
        walletClient.creditSettlementReversal(providerId, settlement.getId(), amount);
        providerNotification.settlementFailed(providerId, settlement.getId(), amount);
        financeAlert.settlementFailed(providerId, settlement.getId(), amount, reason);
        log.error("Settlement {} FAILED for provider {} amount {}: {}",
                settlement.getId(), providerId, amount, reason);
        return settlement;
    }

    // ===================== Reads =====================

    @Transactional(readOnly = true)
    public PaymentTransaction getTransaction(UUID transactionId) {
        return getExisting(transactionId);
    }

    private PaymentTransaction getExisting(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> PaymentException.notFound("Transaction " + transactionId + " not found"));
    }
}
