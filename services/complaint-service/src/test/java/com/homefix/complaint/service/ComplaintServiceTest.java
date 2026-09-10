package com.homefix.complaint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.payment.RefundResult;
import com.homefix.complaint.support.FixedSupportAgentDirectory;
import com.homefix.complaint.support.InMemoryComplaintRepository;
import com.homefix.complaint.support.RecordingComplaintEventPublisher;
import com.homefix.complaint.support.RecordingCustomerNotificationPort;
import com.homefix.complaint.support.RecordingFinanceAlertPort;
import com.homefix.complaint.support.RecordingSettlementHoldPort;
import com.homefix.complaint.support.StubRefundPort;

/**
 * Unit tests for the complaint lifecycle orchestration (Requirement 16).
 *
 * <p>Covers: creation + assignment + 30-minute acknowledgment (16.1, 16.2), the SLA timer
 * triggering escalation to a Senior_Support_Agent with a customer delay notification (16.4), refund
 * approval (16.5) and the REFUND_FAILED rejection flow with customer + Finance_Admin notifications
 * (16.6), settlement-hold placement on DISPUTED (16.7) and release on closure (16.8), and payload
 * validation (16.1). Example-based; no Spring context, DB, or Kafka.
 */
class ComplaintServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final UUID SUPPORT_AGENT = UUID.randomUUID();
    private static final UUID SENIOR_AGENT = UUID.randomUUID();

    private final InMemoryComplaintRepository complaints = new InMemoryComplaintRepository();
    private final FixedSupportAgentDirectory agents =
            new FixedSupportAgentDirectory(SUPPORT_AGENT, SENIOR_AGENT);
    private final RecordingCustomerNotificationPort notifications =
            new RecordingCustomerNotificationPort();
    private final StubRefundPort refunds = StubRefundPort.approving();
    private final RecordingSettlementHoldPort settlements = new RecordingSettlementHoldPort();
    private final RecordingFinanceAlertPort financeAlerts = new RecordingFinanceAlertPort();
    private final RecordingComplaintEventPublisher events = new RecordingComplaintEventPublisher();
    private final ComplaintProperties properties = new ComplaintProperties();

    private ComplaintService serviceAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new ComplaintService(complaints, agents, notifications, refunds, settlements,
                financeAlerts, events, properties, clock);
    }

    private CreateComplaintCommand command(ServicePriority priority) {
        return new CreateComplaintCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ComplaintCategory.POOR_QUALITY, priority, "The work was left incomplete.", List.of());
    }

    // ---- Creation, assignment, acknowledgment (16.1, 16.2) -------------------------------------

    @Test
    void createAssignsAvailableSupportAgentAndAcknowledgesCustomer() {
        ComplaintService service = serviceAt(NOW);

        Complaint complaint = service.createComplaint(command(ServicePriority.STANDARD));

        assertThat(complaint.getStatus()).isEqualTo(ComplaintStatus.OPEN);
        assertThat(complaint.getAgentId()).isEqualTo(SUPPORT_AGENT);
        assertThat(complaint.isAcknowledged()).isTrue();
        // Standard SLA is 72h from submission (16.4).
        assertThat(complaint.getSlaDeadline()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(notifications.acknowledgments()).containsExactly(complaint.getId());
        assertThat(events.created()).hasSize(1);
    }

    @Test
    void emergencyComplaintGetsTwentyFourHourSla() {
        ComplaintService service = serviceAt(NOW);

        Complaint complaint = service.createComplaint(command(ServicePriority.EMERGENCY));

        assertThat(complaint.getSlaDeadline()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void createRejectsDescriptionOverTwoThousandChars() {
        ComplaintService service = serviceAt(NOW);
        String tooLong = "x".repeat(2001);
        CreateComplaintCommand cmd = new CreateComplaintCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ComplaintCategory.DAMAGE, ServicePriority.STANDARD, tooLong,
                List.of());

        assertThatThrownBy(() -> service.createComplaint(cmd))
                .isInstanceOf(ComplaintException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void createRejectsMoreThanFiveAttachments() {
        ComplaintService service = serviceAt(NOW);
        List<AttachmentMetadata> six = List.of(
                new AttachmentMetadata("a", 1), new AttachmentMetadata("b", 1),
                new AttachmentMetadata("c", 1), new AttachmentMetadata("d", 1),
                new AttachmentMetadata("e", 1), new AttachmentMetadata("f", 1));
        CreateComplaintCommand cmd = new CreateComplaintCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ComplaintCategory.DAMAGE, ServicePriority.STANDARD, "ok", six);

        assertThatThrownBy(() -> service.createComplaint(cmd))
                .isInstanceOf(ComplaintException.class);
    }

    @Test
    void createRejectsAttachmentOverTenMegabytes() {
        ComplaintService service = serviceAt(NOW);
        List<AttachmentMetadata> tooBig = List.of(
                new AttachmentMetadata("huge.jpg", 10L * 1024 * 1024 + 1));
        CreateComplaintCommand cmd = new CreateComplaintCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ComplaintCategory.DAMAGE, ServicePriority.STANDARD, "ok", tooBig);

        assertThatThrownBy(() -> service.createComplaint(cmd))
                .isInstanceOf(ComplaintException.class);
    }

    @Test
    void createFailsWhenNoSupportAgentAvailable() {
        agents.setSupportAgent(null);
        ComplaintService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.createComplaint(command(ServicePriority.STANDARD)))
                .isInstanceOf(ComplaintException.class)
                .hasMessageContaining("agent");
    }

    // ---- SLA timer triggering escalation (16.4) ------------------------------------------------

    @Test
    void slaBreachEscalatesToSeniorAgentAndNotifiesCustomer() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.EMERGENCY)).getId();

        // Advance past the 24h emergency SLA and run the sweep.
        Instant later = NOW.plus(Duration.ofHours(25));
        int escalated = serviceAt(later).enforceSlaBreaches();

        assertThat(escalated).isEqualTo(1);
        Complaint after = complaints.findById(complaintId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ComplaintStatus.ESCALATED);
        assertThat(after.isEscalated()).isTrue();
        assertThat(after.getAgentId()).isEqualTo(SENIOR_AGENT);
        assertThat(notifications.delays()).containsExactly(complaintId);
    }

    @Test
    void slaSweepDoesNotEscalateBeforeDeadlineAndIsIdempotent() {
        serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD));

        // Well within the 72h window: no escalation.
        assertThat(serviceAt(NOW.plus(Duration.ofHours(1))).enforceSlaBreaches()).isZero();

        // After breach: escalate once, then a second sweep escalates nothing more.
        Instant breached = NOW.plus(Duration.ofHours(73));
        assertThat(serviceAt(breached).enforceSlaBreaches()).isEqualTo(1);
        assertThat(serviceAt(breached).enforceSlaBreaches()).isZero();
    }

    // ---- Refund approval and rejection (16.5, 16.6) --------------------------------------------

    @Test
    void refundApprovalReturnsApprovedAndDoesNotMarkFailed() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();
        refunds.setNextResult(RefundResult.approved("rf_123"));

        RefundResult result = serviceAt(NOW).approveRefund(complaintId, new BigDecimal("50.00"));

        assertThat(result.approved()).isTrue();
        Complaint after = complaints.findById(complaintId).orElseThrow();
        assertThat(after.getStatus()).isNotEqualTo(ComplaintStatus.REFUND_FAILED);
        assertThat(notifications.refundFailures()).isEmpty();
        assertThat(financeAlerts.alerts()).isEmpty();
    }

    @Test
    void refundRejectionSetsRefundFailedNotifiesCustomerAndAlertsFinance() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();
        refunds.setNextResult(RefundResult.rejected("gateway declined"));

        RefundResult result = serviceAt(NOW).approveRefund(complaintId, new BigDecimal("50.00"));

        assertThat(result.approved()).isFalse();
        Complaint after = complaints.findById(complaintId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ComplaintStatus.REFUND_FAILED);
        assertThat(notifications.refundFailures()).containsExactly(complaintId);
        assertThat(financeAlerts.alerts()).containsExactly(complaintId);
    }

    @Test
    void refundRejectsNonPositiveAmount() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();
        ComplaintService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.approveRefund(complaintId, BigDecimal.ZERO))
                .isInstanceOf(ComplaintException.class);
    }

    // ---- Settlement hold placement and release (16.7, 16.8) ------------------------------------

    @Test
    void disputePlacesSettlementHold() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();

        Complaint disputed = serviceAt(NOW).markDisputed(complaintId);

        assertThat(disputed.getStatus()).isEqualTo(ComplaintStatus.DISPUTED);
        assertThat(disputed.isSettlementHeld()).isTrue();
        assertThat(settlements.holds()).containsExactly(complaintId);
        assertThat(settlements.releases()).isEmpty();
    }

    @Test
    void closingDisputedComplaintReleasesSettlementHold() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();
        serviceAt(NOW).markDisputed(complaintId);

        Instant closeTime = NOW.plus(Duration.ofHours(2));
        Complaint closed = serviceAt(closeTime).closeComplaint(complaintId, ComplaintStatus.RESOLVED);

        assertThat(closed.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(closed.isSettlementHeld()).isFalse();
        assertThat(closed.getResolvedAt()).isEqualTo(closeTime);
        assertThat(settlements.releases()).containsExactly(complaintId);
    }

    @Test
    void closingComplaintWithoutHoldDoesNotReleaseSettlement() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();

        serviceAt(NOW).closeComplaint(complaintId, ComplaintStatus.CLOSED);

        assertThat(settlements.releases()).isEmpty();
    }

    @Test
    void disputeIsIdempotentAndDoesNotDoubleHold() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();
        serviceAt(NOW).markDisputed(complaintId);
        serviceAt(NOW).markDisputed(complaintId);

        assertThat(settlements.holds()).containsExactly(complaintId);
    }

    // ---- Status change notification (16.3) -----------------------------------------------------

    @Test
    void statusChangeNotifiesCustomer() {
        UUID complaintId = serviceAt(NOW)
                .createComplaint(command(ServicePriority.STANDARD)).getId();

        serviceAt(NOW).changeStatus(complaintId, ComplaintStatus.IN_PROGRESS);

        assertThat(notifications.statusChanges()).contains(ComplaintStatus.IN_PROGRESS);
    }
}
