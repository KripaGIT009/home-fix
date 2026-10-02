package com.homefix.complaint.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.complaint.agent.SupportAgentDirectoryPort;
import com.homefix.complaint.alert.FinanceAlertPort;
import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintRefund;
import com.homefix.complaint.domain.ComplaintRefundRepository;
import com.homefix.complaint.domain.ComplaintRefundStatus;
import com.homefix.complaint.domain.ComplaintRepository;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.event.ComplaintEventPublisher;
import com.homefix.complaint.notification.CustomerNotificationPort;
import com.homefix.complaint.payment.RefundPort;
import com.homefix.complaint.payment.RefundResult;
import com.homefix.complaint.settlement.SettlementHoldPort;

/**
 * Orchestrates the complaint lifecycle (Requirement 16):
 *
 * <ol>
 *   <li>Creates a complaint against a completed booking, validates the payload, assigns it to an
 *       available Support_Agent, computes the resolution SLA deadline, and acknowledges the
 *       customer within 30 minutes (16.1, 16.2).</li>
 *   <li>On a Support_Agent status change, notifies the customer via in-app notification within 5
 *       minutes (16.3).</li>
 *   <li>Sweeps for SLA breaches: escalates to a Senior_Support_Agent and notifies the customer of
 *       the delay (16.4).</li>
 *   <li>Coordinates refunds with the Payment Service: at most one recorded refund per complaint,
 *       never inside a database transaction; on rejection sets REFUND_FAILED, notifies the
 *       customer, and alerts Finance_Admin (16.5, 16.6).</li>
 *   <li>Places a provider settlement hold on DISPUTED and releases it on resolution/closure
 *       (16.7, 16.8).</li>
 *   <li>Aggregates complaint statistics for Admin reports (16.9).</li>
 * </ol>
 */
@Service
public class ComplaintService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintService.class);

    /**
     * Complaint states from which a Support_Agent may approve a refund (Requirement 16.5): the
     * complaint is still being worked. Terminal complaints have had their settlement hold released
     * (16.8); REFUND_FAILED complaints are with Finance_Admin for manual processing (16.6).
     */
    static final Set<ComplaintStatus> REFUNDABLE_STATES = EnumSet.of(ComplaintStatus.OPEN,
            ComplaintStatus.IN_PROGRESS, ComplaintStatus.ESCALATED, ComplaintStatus.DISPUTED);

    /**
     * Upper bound on the Admin Portal complaint list (Requirement 19.2). The portal's table takes a
     * bare array with no paging, so the newest {@value} matches are returned and the search box is
     * how staff reach older ones.
     */
    static final int ADMIN_LIST_LIMIT = 200;

    /**
     * States only the system enters, never staff by hand: ESCALATED is the SLA sweep's reassignment
     * to a Senior_Support_Agent (16.4) and REFUND_FAILED is the Payment Service's rejection of a
     * refund (16.6). Setting either from the Admin Portal would show the state without its side
     * effects (no senior agent assigned, no refund on record), so {@link #adminUpdate} refuses them.
     */
    static final Set<ComplaintStatus> SYSTEM_ONLY_STATES =
            EnumSet.of(ComplaintStatus.ESCALATED, ComplaintStatus.REFUND_FAILED);

    private final ComplaintRepository complaintRepository;
    private final ComplaintRefundRepository refundRepository;
    private final SupportAgentDirectoryPort agentDirectory;
    private final CustomerNotificationPort notificationPort;
    private final RefundPort refundPort;
    private final SettlementHoldPort settlementHoldPort;
    private final FinanceAlertPort financeAlertPort;
    private final ComplaintEventPublisher eventPublisher;
    private final ComplaintProperties properties;
    private final ComplaintStatsCalculator statsCalculator;
    private final TransactionOperations transactions;
    private final Clock clock;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public ComplaintService(ComplaintRepository complaintRepository,
                            ComplaintRefundRepository refundRepository,
                            SupportAgentDirectoryPort agentDirectory,
                            CustomerNotificationPort notificationPort,
                            RefundPort refundPort,
                            SettlementHoldPort settlementHoldPort,
                            FinanceAlertPort financeAlertPort,
                            ComplaintEventPublisher eventPublisher,
                            ComplaintProperties properties,
                            TransactionOperations transactions) {
        this(complaintRepository, refundRepository, agentDirectory, notificationPort, refundPort,
                settlementHoldPort, financeAlertPort, eventPublisher, properties, transactions,
                Clock.systemUTC());
    }

    // Visible for testing so unit tests can pin the clock.
    ComplaintService(ComplaintRepository complaintRepository,
                     ComplaintRefundRepository refundRepository,
                     SupportAgentDirectoryPort agentDirectory,
                     CustomerNotificationPort notificationPort,
                     RefundPort refundPort,
                     SettlementHoldPort settlementHoldPort,
                     FinanceAlertPort financeAlertPort,
                     ComplaintEventPublisher eventPublisher,
                     ComplaintProperties properties,
                     TransactionOperations transactions,
                     Clock clock) {
        this.complaintRepository = complaintRepository;
        this.refundRepository = refundRepository;
        this.agentDirectory = agentDirectory;
        this.notificationPort = notificationPort;
        this.refundPort = refundPort;
        this.settlementHoldPort = settlementHoldPort;
        this.financeAlertPort = financeAlertPort;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.statsCalculator = new ComplaintStatsCalculator();
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---- Creation + acknowledgment (Requirement 16.1, 16.2) ------------------------------------

    /**
     * Creates a complaint, assigns it to an available Support_Agent, computes the resolution SLA
     * deadline, publishes {@code ComplaintCreated}, and acknowledges the customer within 30 minutes
     * (Requirement 16.1, 16.2).
     */
    @Transactional
    public Complaint createComplaint(CreateComplaintCommand command) {
        validate(command);

        UUID agentId = agentDirectory.nextAvailableSupportAgent()
                .orElseThrow(() -> ComplaintException.noAgentAvailable(
                        "no support agent is currently available to handle the complaint"));

        Instant now = clock.instant();
        Instant slaDeadline = now.plus(resolutionSla(command.priority()));

        Complaint complaint = Complaint.open(command.bookingId(), command.customerId(),
                command.providerId(), agentId, command.category(), command.priority(),
                command.description(), now, slaDeadline);
        complaintRepository.save(complaint);
        eventPublisher.publishCreated(complaint);

        // Acknowledge the customer within the 30-minute SLA (16.2). Performed synchronously here so
        // it always occurs well within the window; a production build may enqueue via the outbox.
        notificationPort.acknowledgeComplaint(complaint.getCustomerId(), complaint.getId());
        complaint.markAcknowledged();
        complaintRepository.save(complaint);

        log.info("Created complaint {} for booking {} assigned to agent (SLA {})",
                complaint.getId(), complaint.getBookingId(), slaDeadline);
        return complaint;
    }

    // ---- Status change + customer notification (Requirement 16.3) ------------------------------

    /**
     * Applies a Support_Agent status change and notifies the customer within 5 minutes via in-app
     * notification (Requirement 16.3). DISPUTED and terminal transitions additionally drive the
     * settlement-hold lifecycle (16.7, 16.8).
     */
    @Transactional
    public Complaint changeStatus(UUID complaintId, ComplaintStatus newStatus) {
        Complaint complaint = requireComplaint(complaintId);
        // RESOLVED and CLOSED are terminal: the settlement hold has been released (16.8), so the
        // complaint cannot be moved back into the working states (or re-resolved) from here.
        if (complaint.getStatus().isTerminal()) {
            throw ComplaintException.invalidTransition("cannot change a complaint that is already "
                    + complaint.getStatus() + " to " + newStatus);
        }
        if (newStatus == ComplaintStatus.DISPUTED) {
            return markDisputed(complaintId);
        }
        if (newStatus.isTerminal()) {
            return closeComplaint(complaintId, newStatus);
        }
        return applyStatusChange(complaint, newStatus, clock.instant());
    }

    // ---- SLA enforcement sweep (Requirement 16.4) ----------------------------------------------

    /**
     * Escalates every complaint whose resolution SLA has been breached to a Senior_Support_Agent
     * and notifies the customer of the delay (Requirement 16.4). Idempotent per complaint: an
     * already-escalated complaint is skipped by the query.
     *
     * @return the number of complaints escalated by this sweep
     */
    @Transactional
    public int enforceSlaBreaches() {
        Instant now = clock.instant();
        List<Complaint> breached = complaintRepository
                .findByStatusNotInAndEscalatedFalseAndSlaDeadlineLessThanEqual(
                        List.of(ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED), now);

        int escalated = 0;
        for (Complaint complaint : breached) {
            escalated += escalate(complaint, now) ? 1 : 0;
        }
        if (escalated > 0) {
            log.info("SLA sweep escalated {} breached complaint(s)", escalated);
        }
        return escalated;
    }

    /** Escalates a single breached complaint; returns whether it was escalated. */
    private boolean escalate(Complaint complaint, Instant now) {
        UUID senior = agentDirectory.nextAvailableSeniorSupportAgent().orElse(null);
        if (senior == null) {
            log.warn("No senior support agent available to escalate complaint {}", complaint.getId());
            return false;
        }
        ComplaintStatus previous = complaint.getStatus();
        complaint.escalateTo(senior);
        complaintRepository.save(complaint);
        eventPublisher.publishStatusChanged(complaint, previous, now);
        notificationPort.notifyResolutionDelayed(complaint.getCustomerId(), complaint.getId());
        return true;
    }

    // ---- Refund coordination (Requirement 16.5, 16.6) ------------------------------------------

    /**
     * Approves a refund on a complaint and submits it to the Payment Service (Requirement 16.5).
     * On rejection, sets the complaint to REFUND_FAILED, notifies the customer, and alerts
     * Finance_Admin for manual processing (Requirement 16.6).
     *
     * <p><strong>Rules.</strong> A complaint is refunded <em>at most once</em>: the refund is
     * recorded as a {@link ComplaintRefund} whose {@code complaint_id} is unique, and any later
     * approval is refused with 409 {@code REFUND_ALREADY_REQUESTED}, whatever the earlier refund's
     * outcome (a failed refund belongs to Finance_Admin's manual process, 16.6). Only a complaint
     * that is still being worked ({@link #REFUNDABLE_STATES}) can be refunded; a RESOLVED or CLOSED
     * complaint has already had its settlement hold released (16.8), and a REFUND_FAILED one is with
     * Finance_Admin, so both are refused with 409 {@code REFUND_NOT_ALLOWED}. The amount cap against
     * what the customer paid is enforced by the Payment Service, which holds the transaction.
     *
     * <p><strong>Order of operations</strong>, each step in its own short transaction, matching the
     * Payment Service refund flow:
     * <ol>
     *   <li><strong>Validate and reserve</strong> under a row lock on the complaint: an existing
     *       refund is replayed or refused, the state precondition is checked, and a PENDING refund
     *       record is committed. Nothing has been sent to the Payment Service yet.</li>
     *   <li><strong>Call the Payment Service</strong> with no transaction open, passing the record's
     *       stable {@link ComplaintRefund#paymentIdempotencyKey()}.</li>
     *   <li><strong>Record the outcome</strong>: SUCCEEDED with the Payment Service reference, or
     *       FAILED with the reason plus the REFUND_FAILED flow. Customer notification and the
     *       Finance_Admin alert run after that transaction commits.</li>
     * </ol>
     *
     * <p><strong>Idempotency.</strong> A retry carrying the same client {@code idempotencyKey} and
     * amount returns the recorded refund without calling the Payment Service again (409
     * {@code REFUND_IN_PROGRESS} while it is still PENDING); the same key with a different amount is
     * 409 {@code IDEMPOTENCY_KEY_REUSED}. A retry without a key is refused like any second approval,
     * so it can never refund twice either.
     *
     * <p>If the Payment Service call throws, its outcome is unknown: the refund stays PENDING (which
     * keeps blocking further refunds), Finance_Admin is alerted to reconcile it using the idempotency
     * key, and the caller gets 502 {@code REFUND_OUTCOME_UNKNOWN}.
     *
     * @return the recorded refund, SUCCEEDED or FAILED (or the replayed earlier refund)
     */
    public ComplaintRefund approveRefund(ApproveRefundCommand command) {
        validate(command);
        String reason = blankToNull(command.reason());
        String clientKey = blankToNull(command.idempotencyKey());

        // Phase 1: validate and reserve. Any refusal here happens before money moves.
        RefundReservation reservation;
        try {
            reservation = transactions.execute(status -> reserveRefund(command, reason, clientKey));
        } catch (DataIntegrityViolationException e) {
            // The unique complaint_id constraint is the last line of defence behind the row lock.
            throw ComplaintException.refundAlreadyRequested(
                    "complaint " + command.complaintId() + " already has a refund");
        }
        ComplaintRefund refund = reservation.refund();
        if (reservation.replayed()) {
            log.info("Idempotent refund replay for complaint {}: refund {} is {}",
                    refund.getComplaintId(), refund.getId(), refund.getStatus());
            return refund;
        }

        // Phase 2: call the Payment Service with no transaction open.
        String paymentKey = refund.paymentIdempotencyKey();
        RefundResult result;
        try {
            result = refundPort.requestRefund(refund.getBookingId(), refund.getComplaintId(),
                    refund.getAmount(), paymentKey);
        } catch (RuntimeException e) {
            log.error("Refund call for complaint {} (refund {}) threw; outcome unknown: {}",
                    refund.getComplaintId(), refund.getId(), e.getMessage());
            financeAlertPort.refundRequiresManualProcessing(refund.getComplaintId(),
                    refund.getBookingId(), refund.getAmount(),
                    "refund call to the Payment Service failed with an unknown outcome; reconcile "
                            + "using idempotency key " + paymentKey + ": " + e.getMessage());
            throw ComplaintException.refundOutcomeUnknown("refund " + refund.getId()
                    + " was submitted but its outcome is unknown; Finance_Admin has been alerted");
        }
        if (result == null) {
            result = RefundResult.rejected("the Payment Service returned no result");
        }

        // Phase 3: record the outcome.
        return result.approved()
                ? recordRefundSucceeded(refund, result)
                : recordRefundFailed(refund, result);
    }

    /** Phase 1 of {@link #approveRefund}: runs in a transaction holding the complaint's row lock. */
    private RefundReservation reserveRefund(ApproveRefundCommand command, String reason,
                                            String clientKey) {
        Complaint complaint = complaintRepository.findByIdForUpdate(command.complaintId())
                .orElseThrow(() -> ComplaintException.notFound(
                        "complaint not found: " + command.complaintId()));

        // Checked under the lock, so a concurrent approval sees the committed record. Checked before
        // the state precondition so a retry still replays after the complaint has moved on.
        Optional<ComplaintRefund> prior = refundRepository.findByComplaintId(complaint.getId());
        if (prior.isPresent()) {
            return replayRefund(prior.get(), command.amount(), clientKey);
        }

        if (!REFUNDABLE_STATES.contains(complaint.getStatus())) {
            throw ComplaintException.refundNotAllowed("a complaint in status "
                    + complaint.getStatus() + " cannot be refunded; refundable states are "
                    + REFUNDABLE_STATES);
        }

        ComplaintRefund refund = refundRepository.save(ComplaintRefund.reserve(complaint.getId(),
                complaint.getBookingId(), command.amount(), reason, command.approvedBy(), clientKey,
                clock.instant()));
        return new RefundReservation(refund, false);
    }

    private RefundReservation replayRefund(ComplaintRefund prior, BigDecimal amount, String clientKey) {
        if (clientKey == null || !clientKey.equals(prior.getClientIdempotencyKey())) {
            throw ComplaintException.refundAlreadyRequested("complaint " + prior.getComplaintId()
                    + " already has a " + prior.getStatus() + " refund (" + prior.getId()
                    + "); a complaint is refunded at most once");
        }
        if (prior.getAmount().compareTo(amount) != 0) {
            throw ComplaintException.idempotencyKeyReused(
                    "refund idempotency key was already used for amount " + prior.getAmount());
        }
        if (prior.getStatus() == ComplaintRefundStatus.PENDING) {
            throw ComplaintException.refundInProgress(
                    "refund " + prior.getId() + " is still awaiting its Payment Service outcome");
        }
        return new RefundReservation(prior, true);
    }

    /** Phase 3, success: records the Payment Service reference (Requirement 16.5). */
    private ComplaintRefund recordRefundSucceeded(ComplaintRefund reserved, RefundResult result) {
        try {
            ComplaintRefund refund = transactions.execute(status -> {
                ComplaintRefund fresh = requireRefund(reserved.getId());
                fresh.markSucceeded(result.transactionRef(), clock.instant());
                return refundRepository.save(fresh);
            });
            log.info("Refund {} approved for complaint {} ({})", refund.getId(),
                    refund.getComplaintId(), refund.getExternalReference());
            return refund;
        } catch (RuntimeException e) {
            // Money has moved but the record could not be written. It stays PENDING (blocking further
            // refunds) and Finance_Admin must reconcile it by hand.
            log.error("CRITICAL refund {} executed by the Payment Service ({}) but could not be "
                    + "recorded for complaint {}: {}", reserved.getId(), result.transactionRef(),
                    reserved.getComplaintId(), e.getMessage());
            financeAlertPort.refundRequiresManualProcessing(reserved.getComplaintId(),
                    reserved.getBookingId(), reserved.getAmount(),
                    "refund executed by the Payment Service (" + result.transactionRef()
                            + ") but not recorded: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Phase 3, rejection: records FAILED, sets the complaint to REFUND_FAILED, then (after commit)
     * notifies the customer and alerts Finance_Admin for manual processing (Requirement 16.6).
     */
    private ComplaintRefund recordRefundFailed(ComplaintRefund reserved, RefundResult result) {
        String reason = result.failureReason() == null || result.failureReason().isBlank()
                ? "rejected by the Payment Service" : result.failureReason();
        FailedRefund failed;
        try {
            failed = transactions.execute(status -> {
                Instant now = clock.instant();
                ComplaintRefund fresh = requireRefund(reserved.getId());
                fresh.markFailed(reason, now);
                refundRepository.save(fresh);

                Complaint complaint = requireComplaint(fresh.getComplaintId());
                // A complaint closed while the refund was in flight stays closed (16.8).
                if (!complaint.getStatus().isTerminal()) {
                    ComplaintStatus previous = complaint.getStatus();
                    complaint.markRefundFailed();
                    complaintRepository.save(complaint);
                    eventPublisher.publishStatusChanged(complaint, previous, now);
                }
                return new FailedRefund(fresh, complaint.getCustomerId());
            });
        } catch (RuntimeException e) {
            // No money moved, but the refund stays PENDING; Finance_Admin still owns it (16.6).
            log.error("Refund {} was rejected but the rejection could not be recorded for complaint "
                    + "{}: {}", reserved.getId(), reserved.getComplaintId(), e.getMessage());
            financeAlertPort.refundRequiresManualProcessing(reserved.getComplaintId(),
                    reserved.getBookingId(), reserved.getAmount(),
                    reason + " (rejection not recorded: " + e.getMessage() + ")");
            throw e;
        }

        notificationPort.notifyRefundFailed(failed.customerId(), reserved.getComplaintId());
        financeAlertPort.refundRequiresManualProcessing(reserved.getComplaintId(),
                reserved.getBookingId(), reserved.getAmount(), reason);
        log.warn("Refund {} rejected for complaint {}; recorded FAILED and alerted Finance_Admin",
                reserved.getId(), reserved.getComplaintId());
        return failed.refund();
    }

    private ComplaintRefund requireRefund(UUID refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("refund " + refundId + " disappeared"));
    }

    private void validate(ApproveRefundCommand command) {
        List<String> errors = new ArrayList<>();
        if (command.complaintId() == null) {
            errors.add("complaintId is required");
        }
        if (command.approvedBy() == null) {
            errors.add("the approving agent is required");
        }
        BigDecimal amount = command.amount();
        if (amount == null || amount.signum() <= 0) {
            errors.add("refund amount must be positive");
        } else if (amount.stripTrailingZeros().scale() > 2) {
            errors.add("refund amount must have at most 2 decimal places");
        }
        if (command.reason() != null
                && command.reason().length() > ComplaintRefund.REASON_MAX_LENGTH) {
            errors.add("reason exceeds " + ComplaintRefund.REASON_MAX_LENGTH + " characters");
        }
        if (command.idempotencyKey() != null
                && command.idempotencyKey().length() > ComplaintRefund.CLIENT_KEY_MAX_LENGTH) {
            errors.add("idempotencyKey exceeds " + ComplaintRefund.CLIENT_KEY_MAX_LENGTH
                    + " characters");
        }
        if (!errors.isEmpty()) {
            throw ComplaintException.validation("refund request is invalid", errors);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Phase-1 outcome of a refund approval: a fresh reservation, or a replay of a finished one. */
    private record RefundReservation(ComplaintRefund refund, boolean replayed) {
    }

    /** Phase-3 outcome of a rejected refund, carrying what the post-commit notifications need. */
    private record FailedRefund(ComplaintRefund refund, UUID customerId) {
    }

    // ---- Dispute hold lifecycle (Requirement 16.7, 16.8) ---------------------------------------

    /**
     * Sets the complaint (and its booking) to DISPUTED and places a hold on the provider settlement
     * until the complaint is closed (Requirement 16.7). Idempotent: a complaint already holding a
     * settlement is never held twice; if it had been moved back to a working state while the hold
     * stayed in place, only its status returns to DISPUTED.
     */
    @Transactional
    public Complaint markDisputed(UUID complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        if (complaint.getStatus().isTerminal()) {
            throw ComplaintException.invalidTransition(
                    "cannot dispute a complaint that is already " + complaint.getStatus());
        }
        if (complaint.isSettlementHeld()) {
            // The hold outlives a move back to a working state (it is only released on closure,
            // 16.8), so re-disputing such a complaint restores the status without a second hold.
            return complaint.getStatus() == ComplaintStatus.DISPUTED ? complaint
                    : applyStatusChange(complaint, ComplaintStatus.DISPUTED, clock.instant());
        }
        ComplaintStatus previous = complaint.getStatus();
        complaint.placeSettlementHold();
        complaintRepository.save(complaint);

        settlementHoldPort.placeHold(complaint.getBookingId(), complaint.getProviderId(),
                complaint.getId());
        Instant now = clock.instant();
        eventPublisher.publishStatusChanged(complaint, previous, now);
        notificationPort.notifyStatusChange(complaint.getCustomerId(), complaint.getId(),
                complaint.getStatus());
        log.info("Complaint {} set to DISPUTED; provider settlement held", complaintId);
        return complaint;
    }

    /**
     * Resolves or closes a complaint and releases any active provider settlement hold for amounts
     * not subject to a refund (Requirement 16.8). Notifies the customer of the status change (16.3).
     */
    @Transactional
    public Complaint closeComplaint(UUID complaintId, ComplaintStatus terminalStatus) {
        if (!terminalStatus.isTerminal()) {
            throw ComplaintException.validation("closeComplaint requires a terminal status");
        }
        Complaint complaint = requireComplaint(complaintId);
        Instant now = clock.instant();
        ComplaintStatus previous = complaint.getStatus();

        if (terminalStatus == ComplaintStatus.RESOLVED) {
            complaint.resolve(now);
        } else {
            complaint.close(now);
        }

        boolean releaseNeeded = complaint.isSettlementHeld();
        if (releaseNeeded) {
            complaint.releaseSettlementHold();
        }
        complaintRepository.save(complaint);

        if (releaseNeeded) {
            settlementHoldPort.releaseHold(complaint.getBookingId(), complaint.getProviderId(),
                    complaint.getId());
        }
        eventPublisher.publishStatusChanged(complaint, previous, now);
        notificationPort.notifyStatusChange(complaint.getCustomerId(), complaint.getId(),
                complaint.getStatus());
        log.info("Complaint {} {}; settlement hold {}", complaintId, terminalStatus,
                releaseNeeded ? "released" : "not held");
        return complaint;
    }

    // ---- Admin Portal (Requirement 19.2) -------------------------------------------------------

    /**
     * The Admin Portal complaint list: newest first, optionally narrowed to one {@code status}
     * and to complaints whose description or complaint, booking or customer id contains
     * {@code search} (case-insensitive). Bounded by {@link #ADMIN_LIST_LIMIT}; the portal does not
     * page.
     */
    @Transactional(readOnly = true)
    public List<Complaint> searchForAdmin(String search, ComplaintStatus status) {
        Set<ComplaintStatus> statuses = status == null
                ? EnumSet.allOf(ComplaintStatus.class) : EnumSet.of(status);
        String term = blankToNull(search);
        String pattern = term == null ? "%" : "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
        return complaintRepository.searchForAdmin(statuses, pattern,
                PageRequest.of(0, ADMIN_LIST_LIMIT));
    }

    /**
     * Applies a staff update from the Admin Portal: a status change and/or a resolution note.
     *
     * <p>A status that differs from the current one goes through {@link #changeStatus}, so it gets
     * exactly the transitions, settlement-hold handling (16.7, 16.8), event and customer
     * notification (16.3) of the Support_Agent endpoint, and the same 409
     * {@code INVALID_COMPLAINT_TRANSITION} for an illegal move. Re-sending the current status is
     * not a transition: only the note is recorded, with no event or notification. A note is
     * required when the complaint ends up RESOLVED, and replaces any earlier one.
     *
     * <p>The system-driven states ({@link #SYSTEM_ONLY_STATES}) cannot be chosen here: ESCALATED
     * and REFUND_FAILED are only entered through the SLA sweep and the refund flow, which carry
     * their side effects. Both are 409 {@code INVALID_COMPLAINT_TRANSITION} unless they are already
     * the current status (re-sending it to attach a note).
     */
    @Transactional
    public Complaint adminUpdate(UUID complaintId, ComplaintStatus status, String resolutionNote) {
        String note = blankToNull(resolutionNote);
        List<String> errors = new ArrayList<>();
        if (status == null) {
            errors.add("status is required");
        }
        if (status == ComplaintStatus.RESOLVED && note == null) {
            errors.add("resolutionNote is required when resolving a complaint");
        }
        if (note != null && note.length() > Complaint.RESOLUTION_NOTE_MAX_LENGTH) {
            errors.add("resolutionNote exceeds " + Complaint.RESOLUTION_NOTE_MAX_LENGTH
                    + " characters");
        }
        if (!errors.isEmpty()) {
            throw ComplaintException.validation("complaint update is invalid", errors);
        }

        Complaint complaint = requireComplaint(complaintId);
        if (complaint.getStatus() != status) {
            if (SYSTEM_ONLY_STATES.contains(status)) {
                throw ComplaintException.invalidTransition(status + " is set by the system, not by "
                        + "staff; a complaint cannot be moved to it from the Admin Portal");
            }
            complaint = changeStatus(complaintId, status);
        }
        if (note != null) {
            complaint.recordResolutionNote(note);
            complaintRepository.save(complaint);
        }
        log.info("Admin update of complaint {}: status {}{}", complaintId, complaint.getStatus(),
                note != null ? ", resolution note recorded" : "");
        return complaint;
    }

    /** Escapes the {@code LIKE} wildcards so a search term matches literally. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---- Stats aggregation (Requirement 16.9) --------------------------------------------------

    /** Aggregates complaint statistics for Admin reports (Requirement 16.9). */
    @Transactional(readOnly = true)
    public ComplaintStats aggregateStats() {
        return statsCalculator.aggregate(complaintRepository.findAll());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private Complaint applyStatusChange(Complaint complaint, ComplaintStatus newStatus, Instant now) {
        ComplaintStatus previous = complaint.getStatus();
        complaint.changeStatus(newStatus);
        complaintRepository.save(complaint);
        eventPublisher.publishStatusChanged(complaint, previous, now);
        notificationPort.notifyStatusChange(complaint.getCustomerId(), complaint.getId(), newStatus);
        return complaint;
    }

    private Complaint requireComplaint(UUID complaintId) {
        return complaintRepository.findById(complaintId)
                .orElseThrow(() -> ComplaintException.notFound("complaint not found: " + complaintId));
    }

    private java.time.Duration resolutionSla(ServicePriority priority) {
        return priority == ServicePriority.EMERGENCY
                ? properties.getEmergencyResolutionSla()
                : properties.getStandardResolutionSla();
    }

    private void validate(CreateComplaintCommand command) {
        List<String> errors = new ArrayList<>();
        if (command.bookingId() == null) {
            errors.add("bookingId is required");
        }
        if (command.customerId() == null) {
            errors.add("customerId is required");
        }
        if (command.category() == null) {
            errors.add("category is required");
        }
        if (command.priority() == null) {
            errors.add("priority is required");
        }
        if (command.description() == null || command.description().isBlank()) {
            errors.add("description is required");
        } else if (command.description().length() > properties.getMaxDescriptionLength()) {
            errors.add("description exceeds " + properties.getMaxDescriptionLength() + " characters");
        }
        validateAttachments(command.attachments(), errors);

        if (!errors.isEmpty()) {
            throw ComplaintException.validation("complaint submission is invalid", errors);
        }
    }

    private void validateAttachments(List<AttachmentMetadata> attachments, List<String> errors) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        int maxCount = properties.getAttachments().getMaxCount();
        long maxBytes = properties.getAttachments().getMaxBytes();
        if (attachments.size() > maxCount) {
            errors.add("at most " + maxCount + " evidence attachments are allowed");
        }
        for (AttachmentMetadata attachment : attachments) {
            if (attachment.sizeBytes() > maxBytes) {
                errors.add("attachment " + attachment.fileName() + " exceeds the "
                        + maxBytes + "-byte limit");
            }
        }
    }
}
