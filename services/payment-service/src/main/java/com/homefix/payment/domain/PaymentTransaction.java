package com.homefix.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import com.homefix.payment.service.PaymentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A payment transaction aggregate (Requirement 12).
 *
 * <p>Created in {@link TransactionStatus#PENDING}. State changes go exclusively through
 * {@link #transitionTo(TransactionStatus)}, which enforces the transaction state machine
 * (Requirement 12.4, Property 12) so no caller can put the aggregate into an illegal state.
 *
 * <p>Monetary values use {@link BigDecimal} throughout. Raw card numbers are never stored; any
 * gateway payment credential is persisted only as KMS/AES ciphertext (Requirement 12.9).
 *
 * <p><strong>Why {@link DynamicUpdate}.</strong> Several short transactions write this row
 * independently after the charge: the charge flow records the gateway reference, the callback
 * settles the state, the refund flow applies refunds, and the wallet-credit marker is cleared by a
 * targeted bulk update that deliberately does not bump {@code version} (see
 * {@link PaymentTransactionRepository#clearWalletCreditPending(UUID)}). With Hibernate's default
 * all-columns UPDATE, a writer holding an older copy of the row would write every column back,
 * resurrecting a marker that was already cleared. Writing only the columns a transaction actually
 * changed keeps those writers from overwriting each other's fields.
 */
@Entity
@Table(name = "payment_transaction")
@DynamicUpdate
public class PaymentTransaction {

    /** Column width of {@code failure_reason}; longer reasons are truncated rather than failing the write. */
    public static final int FAILURE_REASON_MAX_LENGTH = 512;

    /** Column width of {@code booking_reference}; a longer reference is not stored. */
    public static final int BOOKING_REFERENCE_MAX_LENGTH = 64;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Deterministic idempotency key scoped to (customerId, bookingId) (Requirement 12.3). */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /**
     * The booking's human-readable reference, as the Booking Service reported it when the payment
     * was opened. Sent with the provider's wallet credit so the earnings line names the job; may be
     * {@code null} (payments opened before it was recorded, or a booking without one).
     */
    @Column(name = "booking_reference", length = BOOKING_REFERENCE_MAX_LENGTH, updatable = false)
    private String bookingReference;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Platform fee amount deducted from {@link #amount} to compute provider net (Requirement 12.10). */
    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal platformFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24, updatable = false)
    private PaymentMethod method;

    @Column(name = "gateway", nullable = false, length = 32, updatable = false)
    private String gateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TransactionStatus status;

    /** Reference returned by the gateway when the charge is initiated. */
    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    /**
     * KMS/AES ciphertext of any stored payment credential token (never a raw card number)
     * (Requirement 12.9). May be {@code null} for methods that store nothing (e.g. CASH).
     */
    @Column(name = "payment_credential_encrypted")
    private String paymentCredentialEncrypted;

    /** Amount already refunded (0 for a non-refunded transaction). */
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    /** Number of customer-driven retry attempts made so far (Requirement 12.8). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_reason", length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    /**
     * Gateway event id of the signed callback that settled this transaction (Requirement 12.5).
     * Kept for reconciliation and audit; {@code null} until a callback is applied.
     */
    @Column(name = "callback_event_id", length = 128)
    private String callbackEventId;

    /**
     * When the provider wallet credit for this payment became owed, or {@code null} when nothing is
     * owed (Requirement 12.10, 12.11). Set in the same database transaction that moves the payment
     * to SUCCESS (alongside the PaymentCompleted outbox row), and cleared only after the wallet
     * accepted the credit. The credit itself runs after that commit, so without this durable marker a
     * crash between the commit and the credit would lose the provider's earnings silently; with it,
     * {@code WalletCreditSweeper} re-sends any credit still owed.
     */
    @Column(name = "wallet_credit_pending_since")
    private Instant walletCreditPendingSince;

    /**
     * Why the Provider Service permanently refused this payment's wallet credit (a 4xx such as an
     * unknown provider, or a booking already credited to another provider), or {@code null}. Written
     * together with clearing {@link #walletCreditPendingSince}: a credit that can never succeed is no
     * longer re-sent forever, but it is not forgotten either; Finance_Admin is alerted and
     * reconciles it by hand.
     */
    @Column(name = "wallet_credit_failure", length = FAILURE_REASON_MAX_LENGTH)
    private String walletCreditFailure;

    /**
     * Gateway event id of a signed SUCCEEDED callback that arrived after this attempt was already
     * FAILED, or {@code null}. Such a callback means the gateway captured money on an attempt this
     * service had given up on (a charge still in flight when the attempt was failed, or a gateway
     * that reported a failure before the capture). It cannot be applied as a success, because a later
     * attempt may already have charged the customer for the booking, so it is recorded here and
     * Finance_Admin is alerted to refund or reconcile it; see
     * {@code PaymentService#handleGatewayCallback}.
     */
    @Column(name = "late_capture_event_id", length = 128)
    private String lateCaptureEventId;

    /** When {@link #lateCaptureEventId} was recorded. */
    @Column(name = "late_captured_at")
    private Instant lateCapturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected PaymentTransaction() {
        // JPA
    }

    private PaymentTransaction(String idempotencyKey, UUID customerId, UUID bookingId,
                               String bookingReference, UUID providerId, BigDecimal amount,
                               BigDecimal platformFee, PaymentMethod method, String gateway,
                               String paymentCredentialEncrypted) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.bookingId = bookingId;
        this.bookingReference = bookingReference != null
                && bookingReference.length() <= BOOKING_REFERENCE_MAX_LENGTH ? bookingReference : null;
        this.providerId = providerId;
        this.amount = amount;
        this.platformFee = platformFee;
        this.method = method;
        this.gateway = gateway;
        this.paymentCredentialEncrypted = paymentCredentialEncrypted;
        this.status = TransactionStatus.PENDING;
        this.refundedAmount = BigDecimal.ZERO;
        this.attemptCount = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static PaymentTransaction initiate(String idempotencyKey, UUID customerId, UUID bookingId,
                                              UUID providerId, BigDecimal amount, BigDecimal platformFee,
                                              PaymentMethod method, String gateway,
                                              String paymentCredentialEncrypted) {
        return initiate(idempotencyKey, customerId, bookingId, null, providerId, amount, platformFee,
                method, gateway, paymentCredentialEncrypted);
    }

    /** As the overload without it, also recording the booking's human-readable reference. */
    public static PaymentTransaction initiate(String idempotencyKey, UUID customerId, UUID bookingId,
                                              String bookingReference, UUID providerId, BigDecimal amount,
                                              BigDecimal platformFee, PaymentMethod method, String gateway,
                                              String paymentCredentialEncrypted) {
        return new PaymentTransaction(idempotencyKey, customerId, bookingId, bookingReference, providerId,
                amount, platformFee, method, gateway, paymentCredentialEncrypted);
    }

    /**
     * Transitions to {@code target}, enforcing the state machine (Requirement 12.4, Property 12).
     *
     * @throws PaymentException with 409 semantics if the transition is not permitted.
     */
    public void transitionTo(TransactionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(
                    "Transaction " + id + " cannot transition from " + status + " to " + target
                            + "; permitted targets: " + status.permittedTargets());
        }
        this.status = target;
        this.updatedAt = Instant.now();
        if (target == TransactionStatus.SUCCESS) {
            // A successful payment owes the provider its net earning; recorded with the state change.
            this.walletCreditPendingSince = this.updatedAt;
        }
    }

    /**
     * Records a failed attempt reason without changing state (used before a retry). Reasons longer
     * than the column are truncated so a verbose gateway message cannot fail the whole write.
     */
    public void recordFailureReason(String reason) {
        this.failureReason = truncateReason(reason);
        this.updatedAt = Instant.now();
    }

    /** Truncates a reason to the width of the reason columns. */
    public static String truncateReason(String reason) {
        return reason != null && reason.length() > FAILURE_REASON_MAX_LENGTH
                ? reason.substring(0, FAILURE_REASON_MAX_LENGTH)
                : reason;
    }

    /**
     * Records a signed SUCCEEDED callback that arrived after this attempt was FAILED (see
     * {@link #lateCaptureEventId}). The status stays FAILED; only the first such event is kept.
     *
     * @return {@code true} if this is the first late capture recorded, {@code false} if one already was
     */
    public boolean recordLateCapture(String eventId) {
        if (lateCaptureEventId != null) {
            return false;
        }
        this.lateCaptureEventId = eventId;
        this.lateCapturedAt = Instant.now();
        this.updatedAt = this.lateCapturedAt;
        return true;
    }

    /** Records the gateway event id of the signed callback being applied (Requirement 12.5). */
    public void recordCallbackEvent(String eventId) {
        this.callbackEventId = eventId;
        this.updatedAt = Instant.now();
    }

    /**
     * Registers an additional customer retry attempt.
     *
     * @return the new attempt count.
     */
    public int registerRetryAttempt() {
        this.attemptCount++;
        this.updatedAt = Instant.now();
        return this.attemptCount;
    }

    /**
     * Marks the provider wallet credit as delivered. The service clears the marker through
     * {@link PaymentTransactionRepository#clearWalletCreditPending(UUID)} instead, so the write does
     * not bump {@code version}; this is the in-memory equivalent of that update.
     */
    public void clearWalletCreditPending() {
        this.walletCreditPendingSince = null;
    }

    /** @return whether the provider wallet credit for this payment is still owed. */
    public boolean isWalletCreditPending() {
        return walletCreditPendingSince != null;
    }

    /** @return the provider's net earning = amount - platformFee (Requirement 12.10). */
    public BigDecimal providerNetEarning() {
        return amount.subtract(platformFee);
    }

    /**
     * Checks, without changing anything, that {@code refundAmount} could be applied right now: it is
     * positive, does not push the refunded total past the transaction amount, and the resulting
     * REFUNDED / PARTIALLY_REFUNDED state is reachable from the current state (Requirement 12.7).
     *
     * <p>The refund flow calls this <em>before</em> asking the gateway to move any money, so every
     * refund the gateway executes is one {@link #applyRefund(BigDecimal)} will accept afterwards.
     *
     * @return the state the transaction would move to.
     * @throws PaymentException 400 for a non-positive or over-limit amount, 409 for a state that
     *                          cannot be refunded.
     */
    public TransactionStatus checkRefundable(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw PaymentException.validation("Refund amount must be positive");
        }
        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(amount) > 0) {
            throw PaymentException.validation(
                    "Refund amount " + refundAmount + " would exceed the transaction amount " + amount
                            + " (already refunded " + refundedAmount + ")");
        }
        TransactionStatus target = newRefunded.compareTo(amount) == 0
                ? TransactionStatus.REFUNDED
                : TransactionStatus.PARTIALLY_REFUNDED;
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(
                    "Transaction " + id + " cannot be refunded from " + status + " (would move to "
                            + target + "); permitted targets: " + status.permittedTargets());
        }
        return target;
    }

    /**
     * Applies a refund amount and moves to REFUNDED (full) or PARTIALLY_REFUNDED (partial),
     * enforcing the state machine (Requirement 12.7).
     */
    public void applyRefund(BigDecimal refundAmount) {
        TransactionStatus target = checkRefundable(refundAmount);
        transitionTo(target);
        this.refundedAmount = this.refundedAmount.add(refundAmount);
    }

    public void setGatewayReference(String gatewayReference) {
        this.gatewayReference = gatewayReference;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getGateway() {
        return gateway;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getPaymentCredentialEncrypted() {
        return paymentCredentialEncrypted;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCallbackEventId() {
        return callbackEventId;
    }

    public Instant getWalletCreditPendingSince() {
        return walletCreditPendingSince;
    }

    public String getWalletCreditFailure() {
        return walletCreditFailure;
    }

    /**
     * Marks the wallet credit as permanently refused: no longer owed, with the reason kept. The
     * service writes this through {@link PaymentTransactionRepository#markWalletCreditFailed} so the
     * write does not bump {@code version}; this is the in-memory equivalent of that update.
     */
    public void markWalletCreditFailed(String reason) {
        this.walletCreditPendingSince = null;
        this.walletCreditFailure = truncateReason(reason);
    }

    public String getLateCaptureEventId() {
        return lateCaptureEventId;
    }

    public Instant getLateCapturedAt() {
        return lateCapturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
