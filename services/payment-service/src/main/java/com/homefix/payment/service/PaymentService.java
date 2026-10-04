package com.homefix.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.payment.alert.FinanceAlertPort;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.KmsEncryptionPort;
import com.homefix.payment.domain.PaymentRefund;
import com.homefix.payment.domain.PaymentRefundRepository;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.PaymentTransactionRepository;
import com.homefix.payment.domain.RefundStatus;
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
import com.homefix.payment.gateway.SelfSettlingGatewayPort;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.idempotency.IdempotencyStorePort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.wallet.ProviderWalletClientPort;
import com.homefix.payment.wallet.WalletCreditException;

/**
 * Core payment-domain business logic (Requirement 12 and 14.3-14.4).
 *
 * <p>Responsibilities: idempotent payment initiation scoped to {@code (customerId, bookingId)}
 * (12.3), gateway selection through the {@link PaymentGatewayRegistry} abstraction (12.1),
 * cryptographic callback signature verification and state-machine-guarded state changes (12.4,
 * 12.5), publishing {@code PaymentCompleted} via the outbox plus invoice triggering with retry
 * (12.6), provider wallet credit with retry-then-alert backed by a durable owed-credit marker and
 * sweep (12.10, 12.11), customer retry attempts (12.8), idempotent refunds with staff
 * reconciliation (12.7), and settlement bank transfers (14.3, 14.4).
 *
 * <p><strong>Transaction boundaries.</strong> No external call (gateway, wallet, invoice) and no
 * retry backoff runs while a database transaction is open. Each money-moving flow is split into
 * short transactions through {@link TransactionOperations}: (1) record intent and commit, (2) call
 * the gateway with no transaction open, (3) record the outcome in a new transaction. So the
 * PENDING payment row exists before the charge, a PENDING refund row exists before the gateway
 * refund, and the post-success side effects run after the SUCCESS state has committed.
 *
 * <p>All external dependencies are expressed as ports so the logic is fully unit-testable against
 * in-memory fakes and mocks. All monetary math uses {@link BigDecimal}.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Clock skew tolerated on a signed callback timestamp that lies in the future. */
    private static final Duration CALLBACK_FUTURE_SKEW = Duration.ofMinutes(5);

    /**
     * Attempts at recording a charge result that lost an optimistic-lock race. Each conflict means
     * another versioned write of the row committed in between; in practice that is the gateway
     * callback (a refund cannot start before the gateway reference is recorded, and the wallet-marker
     * clear does not bump the version), so the second attempt normally succeeds.
     */
    private static final int CHARGE_RECORD_ATTEMPTS = 3;

    /** Maximum number of owed wallet credits one sweep re-sends. */
    private static final int WALLET_CREDIT_SWEEP_BATCH = 100;

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRefundRepository refundRepository;
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
    private final TransactionOperations transactions;

    public PaymentService(PaymentTransactionRepository transactionRepository,
                          PaymentRefundRepository refundRepository,
                          SettlementRepository settlementRepository,
                          PaymentGatewayRegistry gatewayRegistry,
                          IdempotencyStorePort idempotencyStore,
                          KmsEncryptionPort kms,
                          ProviderWalletClientPort walletClient,
                          InvoiceTriggerPort invoiceTrigger,
                          PaymentCompletedPublisher paymentCompletedPublisher,
                          FinanceAlertPort financeAlert,
                          ProviderNotificationPort providerNotification,
                          PaymentProperties props,
                          TransactionOperations transactions) {
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
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
        this.transactions = transactions;
    }

    // ===================== Initiate (Requirement 12.2, 12.3, 12.9) =====================

    /**
     * Initiates a payment. Idempotent on {@code (customerId, bookingId)}: a duplicate request
     * returns the original transaction without a new gateway charge (Requirement 12.3, Property 11).
     *
     * <p>The one exception is a FAILED payment: a failed charge must not make the booking unpayable,
     * so when every earlier attempt for the pair is FAILED a request opens the next attempt under its
     * own idempotency key ({@link IdempotencyKeys#forPaymentAttempt}), up to
     * {@code max-payment-attempts}. Duplicates of that attempt are idempotent in turn.
     *
     * <p>The PENDING row is committed <em>before</em> the gateway is charged, so a charge can never
     * exist without a durable record for its callback to find, and only the request that committed
     * the row charges. If the gateway declines, the transaction is marked FAILED. If the charge call
     * itself errors, the outcome at the gateway is unknown, so the transaction stays PENDING (its
     * signed callback settles it) and the caller gets {@code 502 PAYMENT_GATEWAY_ERROR}.
     *
     * <p>A gateway that confirms its own charges ({@link SelfSettlingGatewayPort}, the local
     * simulator only) is settled after the gateway reference has committed, through
     * {@link #handleGatewayCallback}; see {@link #settleIfSelfSettling}.
     */
    public PaymentTransaction initiatePayment(InitiatePaymentCommand cmd) {
        validateInitiate(cmd);

        // First line of defence: an existing live attempt returns the original status.
        AttemptLookup attempts = lookupAttempts(cmd.customerId(), cmd.bookingId());
        if (attempts.current().isPresent()) {
            log.info("Idempotent payment: returning existing transaction {} for customer {} booking {}",
                    attempts.current().get().getId(), cmd.customerId(), cmd.bookingId());
            return attempts.current().get();
        }
        if (attempts.nextAttempt() > maxPaymentAttempts()) {
            throw new PaymentException(HttpStatus.CONFLICT, "PAYMENT_ATTEMPTS_EXHAUSTED",
                    "Booking " + cmd.bookingId() + " already has " + maxPaymentAttempts()
                            + " failed payment attempts; contact support");
        }
        String key = IdempotencyKeys.forPaymentAttempt(
                cmd.customerId(), cmd.bookingId(), attempts.nextAttempt());
        if (attempts.nextAttempt() > 1) {
            log.info("Previous payment attempts for customer {} booking {} FAILED; opening attempt {}",
                    cmd.customerId(), cmd.bookingId(), attempts.nextAttempt());
        }

        PaymentGatewayPort gateway = gatewayRegistry.require(cmd.gatewayId());
        BigDecimal platformFee = resolvePlatformFee(cmd.amount(), cmd.platformFee());

        // Requirement 12.9: never store a raw card number; store only an encrypted credential token.
        String encryptedCredential = cmd.rawPaymentCredential() == null
                ? null
                : kms.encrypt(cmd.rawPaymentCredential());

        PaymentTransaction tx = PaymentTransaction.initiate(key, cmd.customerId(), cmd.bookingId(),
                cmd.bookingReference(), cmd.providerId(), cmd.amount(), platformFee, cmd.method(),
                gateway.gatewayId(), encryptedCredential);

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

        // Phase 1: commit the PENDING row. The unique idempotency_key column is the authoritative
        // guard: a concurrent duplicate that got past the store loses here and returns the winner.
        UUID transactionId = tx.getId();
        try {
            transactions.executeWithoutResult(status -> transactionRepository.save(tx));
        } catch (DataIntegrityViolationException e) {
            log.info("Idempotent payment (unique key): concurrent duplicate for key {}", key);
            return transactionRepository.findByIdempotencyKey(key).orElseThrow(() -> e);
        }

        // Phase 2: charge with no database transaction open.
        GatewayChargeResult result;
        try {
            result = gateway.charge(new GatewayChargeRequest(
                    transactionId, cmd.bookingId(), cmd.customerId(), cmd.amount(), cmd.method()));
        } catch (RuntimeException e) {
            log.error("Gateway charge call threw for transaction {}: {}", transactionId, e.getMessage());
            transactions.executeWithoutResult(status -> {
                PaymentTransaction fresh = getExisting(transactionId);
                if (fresh.getStatus() == TransactionStatus.PENDING) {
                    fresh.recordFailureReason("Charge initiation error: " + e.getMessage());
                    transactionRepository.save(fresh);
                }
            });
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR",
                    "Gateway charge failed for transaction " + transactionId
                            + "; it stays PENDING until the gateway callback settles it");
        }

        // Phase 3: record the gateway reference. A fast callback can commit between our re-read and
        // our write, failing this step on the version check even though the charge succeeded. That
        // must not surface as a 500 or leave the reference unrecorded (a later refund needs it), so
        // the step is re-run against a fresh read; the re-run only changes the status while the
        // transaction is still PENDING, so it never overwrites the state the callback settled.
        OptimisticLockingFailureException conflict = null;
        for (int attempt = 1; attempt <= CHARGE_RECORD_ATTEMPTS; attempt++) {
            PaymentTransaction recorded;
            try {
                recorded = recordChargeResult(transactionId, result);
            } catch (OptimisticLockingFailureException e) {
                conflict = e;
                log.info("Concurrent update of transaction {} while recording its gateway reference "
                        + "(attempt {}); re-reading", transactionId, attempt);
                continue;
            }
            return settleIfSelfSettling(gateway, recorded);
        }
        throw conflict;
    }

    /**
     * Returns the live payment for {@code (customerId, bookingId)}: the latest attempt, unless it
     * FAILED (then there is none and the next request may open a new attempt). Read-only; the booking
     * payment flow calls it before checking the booking status, because once a payment succeeded the
     * booking is no longer in a payable state but the duplicate request must still get its payment.
     */
    public Optional<PaymentTransaction> findActivePayment(UUID customerId, UUID bookingId) {
        return lookupAttempts(customerId, bookingId).current();
    }

    /**
     * Walks the attempt keys 1, 2, ... of {@code (customerId, bookingId)} while they hold FAILED
     * transactions. Attempt n+1 is only ever created after attempt n FAILED, so the walk stops at the
     * first live attempt or the first free number; it is bounded by {@code max-payment-attempts}.
     */
    private AttemptLookup lookupAttempts(UUID customerId, UUID bookingId) {
        int limit = maxPaymentAttempts();
        for (int attempt = 1; attempt <= limit; attempt++) {
            Optional<PaymentTransaction> tx = transactionRepository.findByIdempotencyKey(
                    IdempotencyKeys.forPaymentAttempt(customerId, bookingId, attempt));
            if (tx.isEmpty()) {
                return new AttemptLookup(Optional.empty(), attempt);
            }
            if (tx.get().getStatus() != TransactionStatus.FAILED) {
                return new AttemptLookup(tx, attempt);
            }
        }
        return new AttemptLookup(Optional.empty(), limit + 1);
    }

    private int maxPaymentAttempts() {
        return Math.max(1, props.getMaxPaymentAttempts());
    }

    /**
     * The live attempt for a (customer, booking) pair, if any, and the number the next attempt would
     * take when there is none.
     */
    private record AttemptLookup(Optional<PaymentTransaction> current, int nextAttempt) {
    }

    /**
     * Settles a charge accepted by a {@link SelfSettlingGatewayPort} (the local simulator) by feeding
     * the gateway's own signed SUCCESS callback through {@link #handleGatewayCallback}, so the
     * SUCCESS transition, PaymentCompleted, invoice and wallet credit take the real path.
     *
     * <p>Runs only after phase 3 committed the PENDING row's gateway reference, with no transaction
     * open. A settlement failure is logged and the payment simply stays PENDING: the charge itself
     * succeeded, so the request must not fail after it.
     */
    private PaymentTransaction settleIfSelfSettling(PaymentGatewayPort gateway, PaymentTransaction recorded) {
        if (!(gateway instanceof SelfSettlingGatewayPort selfSettling)
                || recorded.getStatus() != TransactionStatus.PENDING
                || recorded.getGatewayReference() == null) {
            return recorded;
        }
        UUID transactionId = recorded.getId();
        try {
            SelfSettlingGatewayPort.SignedSettlement settlement =
                    selfSettling.settlementFor(transactionId, recorded.getAmount());
            return handleGatewayCallback(transactionId, new GatewayCallback(
                    gateway.gatewayId(), settlement.payload(), settlement.signature()));
        } catch (RuntimeException e) {
            log.warn("Gateway {} could not settle transaction {}; it stays PENDING: {}",
                    gateway.gatewayId(), transactionId, e.getMessage());
            try {
                return getExisting(transactionId);
            } catch (RuntimeException readFailure) {
                return recorded;
            }
        }
    }

    /** Phase 3 of {@link #initiatePayment}: re-reads the row and records the charge result on it. */
    private PaymentTransaction recordChargeResult(UUID transactionId, GatewayChargeResult result) {
        return transactions.execute(status -> {
            PaymentTransaction fresh = getExisting(transactionId);
            fresh.setGatewayReference(result.gatewayReference());
            if (!result.accepted() && fresh.getStatus() == TransactionStatus.PENDING) {
                fresh.recordFailureReason("Gateway declined the charge");
                fresh.transitionTo(TransactionStatus.FAILED);
            }
            return transactionRepository.save(fresh);
        });
    }

    private void validateInitiate(InitiatePaymentCommand cmd) {
        if (cmd.customerId() == null || cmd.bookingId() == null || cmd.providerId() == null) {
            throw PaymentException.validation("customerId, bookingId, and providerId are required");
        }
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            throw PaymentException.validation("Payment amount must be positive");
        }
        requireMoneyScale(cmd.amount(), "Payment amount");
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
            requireMoneyScale(explicitFee, "Platform fee");
            return explicitFee;
        }
        return amount.multiply(props.getDefaultPlatformFeePercent())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * Rejects an amount with more than two decimal places. Every money column is
     * {@code numeric(12,2)}, so Postgres would silently round {@code 33.334} to {@code 33.33} while
     * the gateway was asked for {@code 33.334}: the stored amount would then disagree with what moved
     * at the gateway, with refund totals, with the refund idempotency amount comparison and with the
     * signed callback amount binding. Trailing zeros are not precision ({@code 33.3300} is fine).
     */
    private static void requireMoneyScale(BigDecimal amount, String what) {
        if (amount.stripTrailingZeros().scale() > 2) {
            throw PaymentException.validation(what + " must have at most 2 decimal places");
        }
    }

    // ===================== Gateway callback (Requirement 12.5, 12.6, 12.10) =====================

    /**
     * Handles an asynchronous gateway callback (Requirement 12.5, 12.4).
     *
     * <ol>
     *   <li>Verifies the signature over {@code payload} with the named gateway's secret, before
     *       anything else (no database access for an unauthenticated caller).</li>
     *   <li>Parses the signed payload ({@link SignedCallbackPayload}); the outcome, failure reason,
     *       transaction id and amount come <em>only</em> from it.</li>
     *   <li>Binds it to this request and transaction: the payload's transaction id must equal the
     *       path id, its gateway must equal both the request's and the transaction's gateway, and its
     *       amount must equal the transaction amount. Its timestamp must be within
     *       {@code callback-max-age}.</li>
     *   <li>Replay protection: on a transaction already settled, a callback that agrees with the
     *       settled state is an idempotent no-op (no second event, invoice or wallet credit); one
     *       that contradicts it is rejected with {@code 409 CALLBACK_CONFLICT}.</li>
     *   <li>The one exception: a SUCCEEDED callback for a FAILED attempt is a <em>late capture</em>
     *       (the gateway took the money after this service gave up on the attempt, or after the
     *       gateway itself reported a failure). It is accepted (200, so the gateway stops
     *       re-delivering it), recorded on the attempt ({@link PaymentTransaction#getLateCaptureEventId()}),
     *       which stays FAILED, and Finance_Admin is alerted once to refund or reconcile it. It is
     *       never applied as the booking's payment: a later attempt may already have been charged,
     *       and turning a FAILED attempt into SUCCESS would charge the customer twice.</li>
     * </ol>
     *
     * <p>The state change and the {@code PaymentCompleted} outbox row commit together; invoice
     * triggering and wallet credit (which retry with backoff) run after that commit.
     *
     * @throws PaymentException 400 for an invalid signature, malformed payload, mismatched binding
     *                          or stale timestamp; 404 for an unknown transaction; 409 on conflict.
     */
    public PaymentTransaction handleGatewayCallback(UUID transactionId, GatewayCallback callback) {
        PaymentGatewayPort gateway = gatewayRegistry.require(callback.gatewayId());

        // Requirement 12.5: verify the cryptographic signature BEFORE anything else.
        if (!gateway.verifyCallbackSignature(callback.payload(), callback.signature())) {
            log.warn("SECURITY invalid gateway callback signature for transaction {} gateway {}",
                    transactionId, callback.gatewayId());
            throw PaymentException.invalidSignature(
                    "Invalid callback signature for transaction " + transactionId);
        }

        return settleVerified(transactionId, gateway, SignedCallbackPayload.parse(callback.payload()));
    }

    /**
     * Applies a charge outcome that a gateway adapter has already authenticated in its own way, for a
     * gateway whose confirmations do not arrive in the {@link SignedCallbackPayload} wire format
     * (Razorpay: the Checkout signature plus a read-back of the payment, or its own webhook). The
     * caller has verified the gateway's signature and built {@code outcome} only from verified data;
     * from here on it is handled exactly like a signed callback: bound to the transaction, gateway and
     * amount, checked for age, applied once.
     *
     * @throws PaymentException as for {@link #handleGatewayCallback}, except the signature checks
     */
    public PaymentTransaction settleVerifiedOutcome(UUID transactionId, SignedCallbackPayload outcome) {
        return settleVerified(transactionId, gatewayRegistry.require(outcome.gatewayId()), outcome);
    }

    /** The transaction a gateway reference (e.g. a Razorpay order id) was recorded on, if any. */
    public Optional<PaymentTransaction> findByGatewayReference(String gatewayReference) {
        return transactionRepository.findByGatewayReference(gatewayReference);
    }

    private PaymentTransaction settleVerified(UUID transactionId, PaymentGatewayPort gateway,
                                              SignedCallbackPayload signed) {
        if (!transactionId.equals(signed.transactionId())) {
            log.warn("SECURITY signed callback for transaction {} (event {}) was sent for transaction {}",
                    signed.transactionId(), signed.eventId(), transactionId);
            throw PaymentException.callbackMismatch(
                    "Signed payload is for a different transaction than " + transactionId);
        }
        if (!gateway.gatewayId().equals(signed.gatewayId())) {
            log.warn("SECURITY signed callback names gateway {} but was verified as {} (transaction {})",
                    signed.gatewayId(), gateway.gatewayId(), transactionId);
            throw PaymentException.callbackMismatch(
                    "Signed payload gateway does not match the callback gateway");
        }
        requireFreshCallback(transactionId, signed);

        CallbackApplication applied;
        try {
            applied = applyCallback(transactionId, signed);
        } catch (OptimisticLockingFailureException e) {
            // A concurrent delivery of a callback for this transaction committed first. Re-run once:
            // the fresh read now sees the settled state and takes the idempotent/conflict path.
            log.info("Concurrent callback for transaction {}; re-evaluating against the settled state",
                    transactionId);
            applied = applyCallback(transactionId, signed);
        }

        if (applied.newlySucceeded()) {
            onPaymentSuccess(applied.transaction());
        }
        if (applied.newLateCapture()) {
            PaymentTransaction tx = applied.transaction();
            financeAlert.lateCapture(tx.getId(), tx.getBookingId(), tx.getAmount(),
                    "gateway reported SUCCEEDED (event " + signed.eventId() + ") for an attempt already FAILED"
                            + (tx.getFailureReason() == null ? "" : " (" + tx.getFailureReason() + ")")
                            + "; refund the capture or reconcile it against the booking");
        }
        return applied.transaction();
    }

    private void requireFreshCallback(UUID transactionId, SignedCallbackPayload signed) {
        Instant now = Instant.now();
        if (signed.timestamp().isAfter(now.plus(CALLBACK_FUTURE_SKEW))) {
            log.warn("SECURITY callback for transaction {} is timestamped in the future: {}",
                    transactionId, signed.timestamp());
            throw PaymentException.callbackStale("Callback timestamp is in the future");
        }
        Duration maxAge = props.getCallbackMaxAge();
        if (maxAge != null && !maxAge.isZero() && !maxAge.isNegative()
                && signed.timestamp().isBefore(now.minus(maxAge))) {
            log.warn("SECURITY stale callback for transaction {} timestamped {} (max age {})",
                    transactionId, signed.timestamp(), maxAge);
            throw PaymentException.callbackStale("Callback timestamp is older than " + maxAge);
        }
    }

    /** Applies a verified, bound callback in one short transaction (state change + outbox row). */
    private CallbackApplication applyCallback(UUID transactionId, SignedCallbackPayload signed) {
        return transactions.execute(status -> {
            PaymentTransaction tx = getExisting(transactionId);
            if (!tx.getGateway().equals(signed.gatewayId())) {
                log.warn("SECURITY callback via gateway {} for transaction {} which uses gateway {}",
                        signed.gatewayId(), transactionId, tx.getGateway());
                throw PaymentException.callbackMismatch(
                        "Callback gateway does not match the transaction's gateway");
            }
            if (tx.getAmount().compareTo(signed.amount()) != 0) {
                log.warn("SECURITY callback amount {} does not match transaction {} amount {}",
                        signed.amount(), transactionId, tx.getAmount());
                throw PaymentException.callbackMismatch(
                        "Signed amount does not match the transaction amount");
            }

            if (tx.getStatus() == TransactionStatus.FAILED
                    && signed.outcome() == SignedCallbackPayload.Outcome.SUCCEEDED) {
                // The gateway captured money on an attempt already FAILED. Rejecting it would leave
                // the capture with no record at all (and the gateway re-delivering it); applying it
                // could charge the booking twice, since a later attempt may already be paid. So it
                // is recorded on the attempt, which stays FAILED, and Finance_Admin is alerted.
                boolean first = tx.recordLateCapture(signed.eventId());
                log.error("LATE CAPTURE: SUCCEEDED callback (event {}) for transaction {} booking {} which is "
                                + "already FAILED; recorded for Finance_Admin to refund or reconcile{}",
                        signed.eventId(), transactionId, tx.getBookingId(), first ? "" : " (already recorded)");
                return new CallbackApplication(first ? transactionRepository.save(tx) : tx, false, first);
            }
            if (tx.getStatus() != TransactionStatus.PENDING) {
                if (signed.outcome().agreesWith(tx.getStatus())) {
                    log.info("Duplicate {} callback (event {}) for transaction {} already {}; no-op",
                            signed.outcome(), signed.eventId(), transactionId, tx.getStatus());
                    return new CallbackApplication(tx, false, false);
                }
                log.warn("SECURITY conflicting {} callback (event {}) for transaction {} already {}",
                        signed.outcome(), signed.eventId(), transactionId, tx.getStatus());
                throw PaymentException.callbackConflict("Transaction " + transactionId + " is already "
                        + tx.getStatus() + "; a " + signed.outcome() + " callback cannot be applied");
            }

            tx.recordCallbackEvent(signed.eventId());
            if (signed.outcome() == SignedCallbackPayload.Outcome.SUCCEEDED) {
                tx.transitionTo(TransactionStatus.SUCCESS);
                PaymentTransaction saved = transactionRepository.save(tx);
                // Atomic with the SUCCESS state change: the outbox row commits in this transaction.
                paymentCompletedPublisher.publish(saved);
                return new CallbackApplication(saved, true, false);
            }
            tx.recordFailureReason(signed.failureReason());
            tx.transitionTo(TransactionStatus.FAILED);
            return new CallbackApplication(transactionRepository.save(tx), false, false);
        });
    }

    /**
     * Result of applying a callback: the transaction, whether it moved to SUCCESS just now, and
     * whether a late capture on a FAILED attempt was recorded just now (so Finance_Admin is alerted
     * once, not on every re-delivery).
     */
    private record CallbackApplication(PaymentTransaction transaction, boolean newlySucceeded,
                                       boolean newLateCapture) {
    }

    /**
     * Post-SUCCESS side effects (Requirement 12.6, 12.10, 12.11), run after the SUCCESS state and
     * its PaymentCompleted outbox row have committed: trigger invoice generation with retry, and
     * credit the provider wallet with retry-then-alert. No transaction is open here, so the retry
     * backoff never holds a database connection.
     *
     * <p>The wallet credit is owed durably: the SUCCESS commit also set the transaction's
     * wallet-credit marker ({@link PaymentTransaction#isWalletCreditPending()}), which is cleared
     * only once the wallet accepts the credit. If this process dies before then, or every retry
     * fails, {@link #retryPendingWalletCredits()} re-sends it later. Nothing consumes
     * {@code PaymentCompleted} to credit the wallet, so this marker is the only durable record that
     * the credit is still owed.
     */
    private void onPaymentSuccess(PaymentTransaction tx) {
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
                () -> creditWallet(tx));
        if (walletResult.succeeded()) {
            clearWalletCreditPending(tx.getId());
            return;
        }
        String reason = walletResult.lastError() == null ? "unknown"
                : walletResult.lastError().getMessage();
        if (isPermanent(walletResult.lastError())) {
            log.error("Wallet credit for provider {} booking {} amount {} was refused permanently after "
                            + "{} attempts; it will not be re-sent: {}",
                    tx.getProviderId(), tx.getBookingId(), net, walletResult.attempts(), reason);
            markWalletCreditFailed(tx.getId(), reason);
        } else {
            log.error("Wallet credit failed after {} attempts for provider {} booking {} amount {}; "
                            + "it stays owed and the wallet-credit sweeper will re-send it",
                    walletResult.attempts(), tx.getProviderId(), tx.getBookingId(), net);
        }
        financeAlert.walletCreditFailed(tx.getProviderId(), tx.getBookingId(), net, reason);
    }

    private void creditWallet(PaymentTransaction tx) {
        walletClient.creditEarning(tx.getProviderId(), tx.getBookingId(), tx.getBookingReference(),
                tx.getAmount(), tx.getPlatformFee(), tx.providerNetEarning());
    }

    /** Whether the wallet refused a credit for good, so re-sending it can never succeed. */
    private static boolean isPermanent(Throwable failure) {
        return failure instanceof WalletCreditException wce && wce.isPermanent();
    }

    /**
     * Stops re-sending a credit the wallet refused for good: clears the owed marker and records the
     * reason on the payment, so Finance_Admin (alerted by the caller) can find and reconcile it. A
     * failure here is logged, not thrown; the marker then stays and the sweeper meets the same
     * refusal again later.
     */
    private void markWalletCreditFailed(UUID transactionId, String reason) {
        try {
            transactions.executeWithoutResult(status -> transactionRepository.markWalletCreditFailed(
                    transactionId, PaymentTransaction.truncateReason(reason)));
        } catch (RuntimeException e) {
            log.warn("Wallet credit for transaction {} was refused permanently but could not be marked "
                    + "failed; the sweeper will try it again: {}", transactionId, e.getMessage());
        }
    }

    /**
     * Clears the wallet-credit marker after the wallet accepted the credit. A failure here is logged,
     * not thrown: the credit already happened, and the worst case is that the sweeper re-sends it,
     * which the wallet absorbs because {@link ProviderWalletClientPort#creditEarning} is idempotent per
     * booking.
     */
    private void clearWalletCreditPending(UUID transactionId) {
        try {
            transactions.executeWithoutResult(
                    status -> transactionRepository.clearWalletCreditPending(transactionId));
        } catch (RuntimeException e) {
            log.warn("Wallet credit for transaction {} succeeded but its pending marker could not be "
                    + "cleared; the sweeper will re-send it: {}", transactionId, e.getMessage());
        }
    }

    // ===================== Owed wallet credits (Requirement 12.10, 12.11) =====================

    /**
     * Re-sends provider wallet credits that are still owed: payments whose SUCCESS committed at least
     * {@code wallet-credit-sweep-min-age} ago but whose credit was never confirmed, because the
     * process died between the commit and the credit or because every in-line retry failed. Driven
     * by {@code WalletCreditSweeper}.
     *
     * <p>The minimum age keeps the sweep clear of a credit the callback thread is still retrying.
     * Each owed credit gets one attempt per sweep; a failure is logged and left for the next sweep
     * (Finance_Admin was already alerted when the in-line retries ran out). A <em>permanent</em>
     * refusal ({@link WalletCreditException#isPermanent()}, e.g. 404 for an unknown provider) is
     * different: re-sending it every minute forever changes nothing, so the credit is marked failed
     * with its reason (no longer owed, still on record) and Finance_Admin is alerted, as for a failed
     * settlement. Credits are at-least-once
     * &mdash; a crash after the wallet accepted a credit but before the marker was cleared re-sends
     * it &mdash; which is why the wallet port must treat {@code creditEarning} as idempotent per
     * booking.
     *
     * @return the number of credits delivered by this sweep.
     */
    public int retryPendingWalletCredits() {
        Instant cutoff = Instant.now().minus(props.getWalletCreditSweepMinAge());
        int credited = 0;
        for (PaymentTransaction tx : transactionRepository.findWalletCreditsDue(
                cutoff, PageRequest.of(0, WALLET_CREDIT_SWEEP_BATCH))) {
            try {
                creditWallet(tx);
            } catch (RuntimeException e) {
                if (isPermanent(e)) {
                    log.error("Owed wallet credit for transaction {} provider {} booking {} amount {} "
                                    + "was refused permanently; marking it failed and alerting Finance: {}",
                            tx.getId(), tx.getProviderId(), tx.getBookingId(), tx.providerNetEarning(),
                            e.getMessage());
                    markWalletCreditFailed(tx.getId(), e.getMessage());
                    financeAlert.walletCreditFailed(tx.getProviderId(), tx.getBookingId(),
                            tx.providerNetEarning(), e.getMessage());
                    continue;
                }
                log.error("Owed wallet credit for transaction {} provider {} booking {} amount {} "
                                + "failed again; will retry on the next sweep: {}",
                        tx.getId(), tx.getProviderId(), tx.getBookingId(), tx.providerNetEarning(),
                        e.getMessage());
                continue;
            }
            clearWalletCreditPending(tx.getId());
            credited++;
            log.info("Delivered owed wallet credit for transaction {} provider {} booking {}",
                    tx.getId(), tx.getProviderId(), tx.getBookingId());
        }
        return credited;
    }

    // ===================== Customer retry (Requirement 12.8) =====================

    /**
     * Records a customer-driven retry of a failed/pending payment. Once the configured maximum
     * number of attempts is reached, the transaction is marked permanently FAILED (Requirement 12.8).
     *
     * <p><strong>A charge that may still be in flight is never abandoned.</strong> A PENDING
     * transaction has a charge the gateway may yet capture: its signed callback has not arrived, or
     * the charge call itself failed with an unknown outcome. Failing it would open the booking to a
     * second attempt, and the first charge's late SUCCEEDED callback could then not be applied. So
     * the retry that would fail the transaction is refused with {@code 409 PAYMENT_IN_FLIGHT}, and
     * nothing is recorded, until the transaction is older than {@code pending-charge-timeout}. A
     * capture that still arrives after that is recorded as a late capture and Finance_Admin is
     * alerted (see {@link #handleGatewayCallback}).
     *
     * @return the transaction after registering the attempt.
     * @throws PaymentException 409 {@code NOT_RETRYABLE} for a settled transaction, 409
     *                          {@code PAYMENT_IN_FLIGHT} when this retry would fail a charge that may
     *                          still be in flight
     */
    @Transactional
    public PaymentTransaction retryPayment(UUID transactionId, String failureReason) {
        PaymentTransaction tx = getExisting(transactionId);
        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new PaymentException(HttpStatus.CONFLICT, "NOT_RETRYABLE",
                    "Only a PENDING transaction can be retried; current state " + tx.getStatus());
        }
        if (tx.getAttemptCount() + 1 >= props.getMaxCustomerRetries() && chargeMayBeInFlight(tx)) {
            log.info("Refusing to fail transaction {}: its charge may still be in flight (created {})",
                    transactionId, tx.getCreatedAt());
            throw new PaymentException(HttpStatus.CONFLICT, "PAYMENT_IN_FLIGHT",
                    "The charge for transaction " + transactionId + " may still complete at the gateway; "
                            + "wait for its outcome before giving up on it");
        }
        tx.recordFailureReason(failureReason);
        int attempts = tx.registerRetryAttempt();
        if (attempts >= props.getMaxCustomerRetries()) {
            tx.transitionTo(TransactionStatus.FAILED);
            log.info("Transaction {} permanently FAILED after {} attempts", transactionId, attempts);
        }
        return transactionRepository.save(tx);
    }

    /**
     * Whether a PENDING transaction's charge may still be captured: it was opened less than
     * {@code pending-charge-timeout} ago. A non-positive timeout disables the guard.
     */
    private boolean chargeMayBeInFlight(PaymentTransaction tx) {
        Duration timeout = props.getPendingChargeTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        return tx.getCreatedAt().isAfter(Instant.now().minus(timeout));
    }

    // ===================== Refund (Requirement 12.7) =====================

    /**
     * Refunds a transaction fully or partially via the gateway, moving it to REFUNDED or
     * PARTIALLY_REFUNDED (Requirement 12.7).
     *
     * <p>Order of operations, each step in its own short transaction:
     * <ol>
     *   <li><strong>Validate and reserve</strong> under a row lock on the transaction: state and
     *       amount are checked with {@link PaymentTransaction#checkRefundable(BigDecimal)}, and a
     *       PENDING {@link PaymentRefund} is committed under a unique idempotency key. Nothing has
     *       been sent to the gateway yet, so an invalid refund never moves money.</li>
     *   <li><strong>Call the gateway</strong> with no transaction open, passing the refund id as the
     *       gateway-side idempotency key.</li>
     *   <li><strong>Record the outcome</strong>: apply the refund to the transaction and mark the
     *       refund SUCCEEDED; or, on an explicit gateway rejection, mark it FAILED, alert
     *       Finance_Admin and return {@code 502 REFUND_GATEWAY_ERROR}.</li>
     * </ol>
     *
     * <p><strong>Unknown outcome.</strong> If the gateway call throws (timeout, connection reset),
     * the gateway may or may not have executed the refund. The refund then stays PENDING,
     * Finance_Admin is alerted and the caller gets {@code 502 REFUND_OUTCOME_UNKNOWN}. It must
     * <em>not</em> be marked FAILED: that would invite a retry under a new idempotency key, which
     * creates a new refund id &mdash; a new gateway idempotency key &mdash; so the gateway could not
     * de-duplicate it and the customer would be refunded twice.
     *
     * <p>Idempotency: {@code idempotencyKey} is client-supplied and scoped to the transaction. A
     * retry with the same key and amount returns the original result without calling the gateway
     * again if it SUCCEEDED, or the same 502 if the gateway rejected it. If it is still PENDING, the
     * retry re-sends it to the gateway under the <em>same</em> refund id &mdash; safe, because the
     * gateway de-duplicates on that id &mdash; and records whatever the gateway answers; that is how a
     * caller recovers from {@code REFUND_OUTCOME_UNKNOWN}. Reusing a key with a different amount is
     * 409 {@code IDEMPOTENCY_KEY_REUSED}. While any refund of the transaction is PENDING, a refund
     * with a different key is 409 {@code REFUND_IN_PROGRESS}; staff can also settle a stuck PENDING
     * refund with {@link #reconcileRefund(UUID, UUID)}.
     */
    public PaymentTransaction refund(UUID transactionId, BigDecimal refundAmount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw PaymentException.validation("A refund idempotency key is required");
        }
        if (idempotencyKey.length() > IdempotencyKeys.MAX_CLIENT_REFUND_KEY_LENGTH) {
            throw PaymentException.validation("Refund idempotency key must be at most "
                    + IdempotencyKeys.MAX_CLIENT_REFUND_KEY_LENGTH + " characters");
        }
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw PaymentException.validation("Refund amount must be positive");
        }
        requireMoneyScale(refundAmount, "Refund amount");
        String key = IdempotencyKeys.forRefund(transactionId, idempotencyKey);

        // Phase 1: validate and reserve (or find the earlier attempt). Any rejection here happens
        // before money moves.
        RefundReservation reservation = transactions.execute(
                status -> reserveRefund(transactionId, refundAmount, key));
        if (reservation.replayed()) {
            log.info("Idempotent refund: key {} already succeeded for transaction {}", key, transactionId);
            return reservation.transaction();
        }
        return executeRefund(reservation.transaction(), reservation.refund());
    }

    /**
     * Settles a refund stuck in PENDING (staff-only, FINANCE tier): re-sends it to the gateway under
     * its original refund id and records the outcome, exactly as a same-key retry of
     * {@link #refund} would. Safe whether or not the gateway executed the earlier attempt, because
     * the gateway de-duplicates on the refund id: if it already refunded, it reports that refund
     * again instead of executing a second one. This is also how Finance_Admin repairs a refund that
     * executed at the gateway but could not be recorded.
     *
     * @return the transaction after the outcome is recorded; unchanged if the refund had already
     *         SUCCEEDED.
     * @throws PaymentException 404 {@code REFUND_NOT_FOUND} if the refund does not exist or belongs
     *                          to another transaction, 409 {@code REFUND_NOT_PENDING} if it FAILED,
     *                          502 as for {@link #refund}.
     */
    public PaymentTransaction reconcileRefund(UUID transactionId, UUID refundId) {
        RefundReservation target = transactions.execute(status -> {
            PaymentRefund refund = refundRepository.findById(refundId)
                    .filter(r -> r.getTransactionId().equals(transactionId))
                    .orElseThrow(() -> new PaymentException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                            "Refund " + refundId + " of transaction " + transactionId + " not found"));
            PaymentTransaction tx = getExisting(transactionId);
            return switch (refund.getStatus()) {
                case SUCCEEDED -> new RefundReservation(tx, refund, true);
                case PENDING -> new RefundReservation(tx, refund, false);
                case FAILED -> throw new PaymentException(HttpStatus.CONFLICT, "REFUND_NOT_PENDING",
                        "Refund " + refundId + " was rejected by the gateway; there is nothing to reconcile");
            };
        });
        if (target.replayed()) {
            log.info("Reconcile: refund {} of transaction {} already SUCCEEDED; nothing to do",
                    refundId, transactionId);
            return target.transaction();
        }
        log.warn("Reconciling PENDING refund {} of transaction {}: re-sending it to the gateway",
                refundId, transactionId);
        return executeRefund(target.transaction(), target.refund());
    }

    /**
     * Phases 2 and 3 of {@link #refund}: sends a PENDING refund to the gateway under its own id and
     * records the outcome. Used for a fresh reservation and for a re-send of a PENDING one.
     */
    private PaymentTransaction executeRefund(PaymentTransaction tx, PaymentRefund refund) {
        UUID transactionId = tx.getId();
        UUID refundId = refund.getId();
        BigDecimal amount = refund.getAmount();

        // Phase 2: call the gateway with no transaction open.
        PaymentGatewayPort gateway = gatewayRegistry.require(tx.getGateway());
        GatewayRefundResult result;
        try {
            result = gateway.refund(new GatewayRefundRequest(
                    tx.getGatewayReference(), amount, refundId.toString()));
        } catch (RuntimeException e) {
            throw refundOutcomeUnknown(tx, refundId, amount, e);
        }

        if (!result.succeeded()) {
            // An explicit rejection: the gateway answered, and no money moved.
            String reason = "gateway rejected refund";
            transactions.executeWithoutResult(status -> {
                PaymentRefund pending = requireRefund(refundId);
                pending.markFailed(reason);
                refundRepository.save(pending);
            });
            log.error("Gateway refund failed for transaction {} refund {}: {}", transactionId, refundId, reason);
            financeAlert.refundFailed(transactionId, tx.getBookingId(), amount, reason);
            throw new PaymentException(HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR",
                    "Gateway refund failed for transaction " + transactionId);
        }

        // Phase 3: record the executed refund.
        String gatewayRefundReference = result.gatewayRefundReference();
        try {
            return transactions.execute(status -> {
                PaymentTransaction fresh = getExisting(transactionId);
                PaymentRefund executed = requireRefund(refundId);
                if (executed.getStatus() == RefundStatus.SUCCEEDED) {
                    // A concurrent re-send of this same refund already recorded it; the gateway
                    // de-duplicated the two calls, so there is nothing more to apply.
                    return fresh;
                }
                fresh.applyRefund(amount);
                executed.markSucceeded(gatewayRefundReference);
                refundRepository.save(executed);
                return transactionRepository.save(fresh);
            });
        } catch (RuntimeException e) {
            // Money has moved at the gateway but the record could not be written. The refund stays
            // PENDING (blocking further refunds) until a same-key retry or a reconcile re-sends it
            // and records the gateway's (de-duplicated) answer.
            log.error("CRITICAL refund {} executed at gateway ({}) but could not be recorded for "
                    + "transaction {}: {}", refundId, gatewayRefundReference, transactionId, e.getMessage());
            financeAlert.refundFailed(transactionId, tx.getBookingId(), amount,
                    "refund executed at gateway (" + gatewayRefundReference
                            + ") but not recorded: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Handles a gateway refund call that threw: the outcome is unknown, so the refund stays PENDING
     * with the error noted on it, Finance_Admin is alerted, and the caller is told to retry with the
     * <em>same</em> key (never a new one, which would let the gateway refund twice).
     */
    private PaymentException refundOutcomeUnknown(PaymentTransaction tx, UUID refundId, BigDecimal amount,
                                                  RuntimeException cause) {
        String reason = "gateway refund call failed, outcome unknown: " + cause.getMessage();
        log.error("Gateway refund call threw for transaction {} refund {}; it stays PENDING: {}",
                tx.getId(), refundId, cause.getMessage());
        financeAlert.refundFailed(tx.getId(), tx.getBookingId(), amount, reason + "; refund " + refundId
                + " stays PENDING until re-sent with the same idempotency key or reconciled");
        transactions.executeWithoutResult(status -> {
            PaymentRefund refund = requireRefund(refundId);
            if (refund.getStatus() == RefundStatus.PENDING) {
                refund.recordUnknownOutcome(reason);
                refundRepository.save(refund);
            }
        });
        return new PaymentException(HttpStatus.BAD_GATEWAY, "REFUND_OUTCOME_UNKNOWN",
                "The gateway did not confirm refund " + refundId + " of transaction " + tx.getId()
                        + "; it stays PENDING. Retry with the same idempotency key to re-send it safely");
    }

    /** Phase 1 of {@link #refund}: runs inside a transaction holding the transaction's row lock. */
    private RefundReservation reserveRefund(UUID transactionId, BigDecimal refundAmount, String key) {
        PaymentTransaction tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> PaymentException.notFound("Transaction " + transactionId + " not found"));

        // Checked under the lock, so a concurrent request with the same key sees the committed row.
        Optional<PaymentRefund> prior = refundRepository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            return replayRefund(prior.get(), tx, refundAmount);
        }

        if (tx.getStatus() != TransactionStatus.SUCCESS
                && tx.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            throw PaymentException.invalidTransition(
                    "Only a SUCCESS or PARTIALLY_REFUNDED transaction can be refunded; current state "
                            + tx.getStatus());
        }
        if (tx.getGatewayReference() == null) {
            // Without the charge reference the gateway cannot tell which charge to refund.
            throw new PaymentException(HttpStatus.CONFLICT, "GATEWAY_REFERENCE_MISSING",
                    "Transaction " + transactionId + " has no recorded gateway charge reference; "
                            + "it must be reconciled with the gateway before it can be refunded");
        }
        if (refundRepository.existsByTransactionIdAndStatus(transactionId, RefundStatus.PENDING)) {
            throw new PaymentException(HttpStatus.CONFLICT, "REFUND_IN_PROGRESS",
                    "Another refund of transaction " + transactionId + " is still in progress");
        }
        // Requirement 12.7: validate amount and target state BEFORE the gateway moves any money.
        tx.checkRefundable(refundAmount);

        PaymentRefund refund = refundRepository.save(PaymentRefund.reserve(transactionId, key, refundAmount));
        return new RefundReservation(tx, refund, false);
    }

    private RefundReservation replayRefund(PaymentRefund prior, PaymentTransaction tx, BigDecimal refundAmount) {
        if (prior.getAmount().compareTo(refundAmount) != 0) {
            throw new PaymentException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Refund idempotency key was already used for amount " + prior.getAmount());
        }
        return switch (prior.getStatus()) {
            case SUCCEEDED -> new RefundReservation(tx, prior, true);
            case PENDING -> {
                // Outcome unknown (or still in flight): re-send under the same refund id.
                log.info("Refund {} of transaction {} is still PENDING; re-sending it under the same id",
                        prior.getId(), tx.getId());
                yield new RefundReservation(tx, prior, false);
            }
            case FAILED -> throw new PaymentException(HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR",
                    "Refund with this idempotency key was rejected by the gateway; "
                            + "use a new idempotency key to try again");
        };
    }

    private PaymentRefund requireRefund(UUID refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " disappeared"));
    }

    /**
     * Phase-1 outcome of a refund: a PENDING refund to send to the gateway (fresh, or an earlier
     * attempt to re-send), or a replay of one that already succeeded.
     */
    private record RefundReservation(PaymentTransaction transaction, PaymentRefund refund, boolean replayed) {
    }

    // ===================== Settlement bank transfer (Requirement 14.3, 14.4) =====================

    /**
     * Initiates a settlement bank transfer for a provider (Requirement 14.3): records the settlement
     * as PROCESSING and commits, then initiates the transfer via the gateway with no transaction
     * open. On success the settlement moves to COMPLETED; on failure it moves to FAILED, and after
     * that commits the amount is credited back to the provider wallet and both the provider and
     * Finance_Admin are notified (Requirement 14.4).
     */
    public Settlement initiateSettlement(UUID providerId, BigDecimal amount, String bankAccountRef,
                                         String gatewayId) {
        if (amount == null || amount.signum() <= 0) {
            throw PaymentException.validation("Settlement amount must be positive");
        }
        requireMoneyScale(amount, "Settlement amount");
        if (bankAccountRef == null || bankAccountRef.isBlank()) {
            throw PaymentException.validation("A destination bank account reference is required");
        }
        PaymentGatewayPort gateway = gatewayRegistry.require(gatewayId);

        // Requirement 12.9/4.9: store the bank account reference encrypted at rest.
        String encryptedRef = kms.encrypt(bankAccountRef);
        Settlement processing = transactions.execute(status -> {
            Settlement settlement = Settlement.initiate(providerId, amount, encryptedRef);
            settlement.transitionTo(SettlementStatus.PROCESSING);
            return settlementRepository.save(settlement);
        });
        UUID settlementId = processing.getId();

        GatewayTransferResult result;
        String failure;
        try {
            result = gateway.transfer(new GatewayTransferRequest(providerId, amount, bankAccountRef));
            failure = result.succeeded() ? null : "gateway rejected transfer";
        } catch (RuntimeException e) {
            result = null;
            failure = e.getMessage();
        }

        if (failure == null) {
            String reference = result.gatewayTransferReference();
            return transactions.execute(status -> {
                Settlement settlement = requireSettlement(settlementId);
                settlement.setGatewayReference(reference);
                settlement.transitionTo(SettlementStatus.COMPLETED);
                return settlementRepository.save(settlement);
            });
        }
        return failSettlement(settlementId, providerId, amount, failure);
    }

    private Settlement failSettlement(UUID settlementId, UUID providerId, BigDecimal amount, String reason) {
        Settlement failed = transactions.execute(status -> {
            Settlement settlement = requireSettlement(settlementId);
            settlement.recordFailure(reason);
            settlement.transitionTo(SettlementStatus.FAILED);
            return settlementRepository.save(settlement);
        });
        // Requirement 14.4: credit the amount back to the wallet, notify provider + Finance_Admin.
        walletClient.creditSettlementReversal(providerId, settlementId, amount);
        providerNotification.settlementFailed(providerId, settlementId, amount);
        financeAlert.settlementFailed(providerId, settlementId, amount, reason);
        log.error("Settlement {} FAILED for provider {} amount {}: {}",
                settlementId, providerId, amount, reason);
        return failed;
    }

    private Settlement requireSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalStateException("Settlement " + settlementId + " disappeared"));
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
