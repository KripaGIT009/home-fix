package com.homefix.complaint.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.complaint.agent.SupportAgentDirectoryPort;
import com.homefix.complaint.alert.FinanceAlertPort;
import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
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
 *   <li>Coordinates refunds with the Payment Service; on rejection sets REFUND_FAILED, notifies the
 *       customer, and alerts Finance_Admin (16.5, 16.6).</li>
 *   <li>Places a provider settlement hold on DISPUTED and releases it on resolution/closure
 *       (16.7, 16.8).</li>
 *   <li>Aggregates complaint statistics for Admin reports (16.9).</li>
 * </ol>
 */
@Service
public class ComplaintService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintService.class);

    private final ComplaintRepository complaintRepository;
    private final SupportAgentDirectoryPort agentDirectory;
    private final CustomerNotificationPort notificationPort;
    private final RefundPort refundPort;
    private final SettlementHoldPort settlementHoldPort;
    private final FinanceAlertPort financeAlertPort;
    private final ComplaintEventPublisher eventPublisher;
    private final ComplaintProperties properties;
    private final ComplaintStatsCalculator statsCalculator;
    private final Clock clock;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public ComplaintService(ComplaintRepository complaintRepository,
                            SupportAgentDirectoryPort agentDirectory,
                            CustomerNotificationPort notificationPort,
                            RefundPort refundPort,
                            SettlementHoldPort settlementHoldPort,
                            FinanceAlertPort financeAlertPort,
                            ComplaintEventPublisher eventPublisher,
                            ComplaintProperties properties) {
        this(complaintRepository, agentDirectory, notificationPort, refundPort, settlementHoldPort,
                financeAlertPort, eventPublisher, properties, Clock.systemUTC());
    }

    // Visible for testing so unit tests can pin the clock.
    ComplaintService(ComplaintRepository complaintRepository,
                     SupportAgentDirectoryPort agentDirectory,
                     CustomerNotificationPort notificationPort,
                     RefundPort refundPort,
                     SettlementHoldPort settlementHoldPort,
                     FinanceAlertPort financeAlertPort,
                     ComplaintEventPublisher eventPublisher,
                     ComplaintProperties properties,
                     Clock clock) {
        this.complaintRepository = complaintRepository;
        this.agentDirectory = agentDirectory;
        this.notificationPort = notificationPort;
        this.refundPort = refundPort;
        this.settlementHoldPort = settlementHoldPort;
        this.financeAlertPort = financeAlertPort;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.statsCalculator = new ComplaintStatsCalculator();
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
     * Submits a refund request to the Payment Service for an approved complaint (Requirement 16.5).
     * On rejection, sets the complaint to REFUND_FAILED, notifies the customer, and alerts
     * Finance_Admin for manual processing (Requirement 16.6).
     *
     * @return the {@link RefundResult} from the Payment Service
     */
    @Transactional
    public RefundResult approveRefund(UUID complaintId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ComplaintException.validation("refund amount must be positive");
        }
        Complaint complaint = requireComplaint(complaintId);

        RefundResult result = refundPort.requestRefund(
                complaint.getBookingId(), complaint.getId(), amount);

        Instant now = clock.instant();
        if (result.approved()) {
            log.info("Refund approved for complaint {}", complaintId);
            return result;
        }

        ComplaintStatus previous = complaint.getStatus();
        complaint.markRefundFailed();
        complaintRepository.save(complaint);
        eventPublisher.publishStatusChanged(complaint, previous, now);

        notificationPort.notifyRefundFailed(complaint.getCustomerId(), complaint.getId());
        financeAlertPort.refundRequiresManualProcessing(
                complaint.getId(), complaint.getBookingId(), amount, result.failureReason());
        log.warn("Refund rejected for complaint {}; marked REFUND_FAILED and alerted Finance_Admin",
                complaintId);
        return result;
    }

    // ---- Dispute hold lifecycle (Requirement 16.7, 16.8) ---------------------------------------

    /**
     * Sets the complaint (and its booking) to DISPUTED and places a hold on the provider settlement
     * until the complaint is closed (Requirement 16.7). Idempotent: a complaint already holding a
     * settlement is unaffected.
     */
    @Transactional
    public Complaint markDisputed(UUID complaintId) {
        Complaint complaint = requireComplaint(complaintId);
        if (complaint.getStatus().isTerminal()) {
            throw ComplaintException.invalidTransition(
                    "cannot dispute a complaint that is already " + complaint.getStatus());
        }
        if (complaint.isSettlementHeld()) {
            return complaint;
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
