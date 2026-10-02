package com.homefix.complaint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintRefund;
import com.homefix.complaint.domain.ComplaintRefundStatus;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.payment.RefundPort;
import com.homefix.complaint.payment.RefundResult;
import com.homefix.complaint.support.FixedSupportAgentDirectory;
import com.homefix.complaint.support.InMemoryComplaintRefundRepository;
import com.homefix.complaint.support.InMemoryComplaintRepository;
import com.homefix.complaint.support.MarkingTransactionOperations;
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
 * (16.6) including the refund's state precondition, one-refund-per-complaint rule, idempotent
 * replay, and the port being called with no transaction open, settlement-hold placement on
 * DISPUTED (16.7) and release on closure (16.8), and payload
 * validation (16.1). Example-based; no Spring context, DB, or Kafka.
 */
class ComplaintServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final UUID SUPPORT_AGENT = UUID.randomUUID();
    private static final UUID SENIOR_AGENT = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();

    private final InMemoryComplaintRepository complaints = new InMemoryComplaintRepository();
    private final InMemoryComplaintRefundRepository refundRecords =
            new InMemoryComplaintRefundRepository();
    private final MarkingTransactionOperations transactions = new MarkingTransactionOperations();
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
        return new ComplaintService(complaints, refundRecords, agents, notifications, refunds,
                settlements, financeAlerts, events, properties, transactions, clock);
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

    private UUID newComplaint() {
        return serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();
    }

    private static ApproveRefundCommand refund(UUID complaintId, String amount, String key) {
        return new ApproveRefundCommand(complaintId, new BigDecimal(amount), "tap leaked again",
                APPROVER, key);
    }

    private static void assertErrorCode(Throwable thrown, String errorCode) {
        assertThat(thrown).isInstanceOf(ComplaintException.class);
        assertThat(((ComplaintException) thrown).getErrorCode()).isEqualTo(errorCode);
    }

    @Test
    void refundApprovalRecordsSucceededRefundWithAuditTrail() {
        UUID complaintId = newComplaint();
        refunds.setNextResult(RefundResult.approved("rf_123"));

        ComplaintRefund refund = serviceAt(NOW).approveRefund(refund(complaintId, "50.00", null));

        assertThat(refund.getStatus()).isEqualTo(ComplaintRefundStatus.SUCCEEDED);
        assertThat(refund.getComplaintId()).isEqualTo(complaintId);
        assertThat(refund.getAmount()).isEqualByComparingTo("50.00");
        assertThat(refund.getReason()).isEqualTo("tap leaked again");
        assertThat(refund.getApprovedBy()).isEqualTo(APPROVER);
        assertThat(refund.getExternalReference()).isEqualTo("rf_123");
        assertThat(refund.getRequestedAt()).isEqualTo(NOW);
        assertThat(refund.getCompletedAt()).isEqualTo(NOW);
        assertThat(refundRecords.findByComplaintId(complaintId)).containsSame(refund);

        Complaint after = complaints.findById(complaintId).orElseThrow();
        assertThat(after.getStatus()).isNotEqualTo(ComplaintStatus.REFUND_FAILED);
        assertThat(notifications.refundFailures()).isEmpty();
        assertThat(financeAlerts.alerts()).isEmpty();
    }

    @Test
    void refundPortIsCalledWithNoTransactionOpenAndAStableIdempotencyKey() {
        UUID complaintId = newComplaint();

        ComplaintRefund refund = serviceAt(NOW).approveRefund(refund(complaintId, "50.00", null));

        assertThat(refunds.calledInTransaction()).containsExactly(false);
        // Reserve, then record the outcome: two short transactions around the call.
        assertThat(transactions.executions()).isEqualTo(2);
        assertThat(refunds.idempotencyKeys()).containsExactly(refund.paymentIdempotencyKey());
        assertThat(refund.paymentIdempotencyKey())
                .contains(refund.getId().toString())
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void refundRejectionRecordsFailedSetsRefundFailedNotifiesCustomerAndAlertsFinance() {
        UUID complaintId = newComplaint();
        refunds.setNextResult(RefundResult.rejected("gateway declined"));

        ComplaintRefund refund = serviceAt(NOW).approveRefund(refund(complaintId, "50.00", null));

        assertThat(refund.getStatus()).isEqualTo(ComplaintRefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("gateway declined");
        assertThat(refund.getExternalReference()).isNull();
        Complaint after = complaints.findById(complaintId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ComplaintStatus.REFUND_FAILED);
        // The status-change event joins the transaction that records the failure (outbox MANDATORY).
        assertThat(events.statusChanges()).last()
                .extracting(RecordingComplaintEventPublisher.StatusChange::to)
                .isEqualTo(ComplaintStatus.REFUND_FAILED);
        assertThat(events.statusChangesInTransaction()).last().isEqualTo(true);
        assertThat(notifications.refundFailures()).containsExactly(complaintId);
        assertThat(financeAlerts.alerts()).containsExactly(complaintId);
        assertThat(financeAlerts.reasons()).containsExactly("gateway declined");
        assertThat(refunds.calledInTransaction()).containsExactly(false);
    }

    @Test
    void secondRefundOfTheSameComplaintIsRefusedAndRefundsOnce() {
        UUID complaintId = newComplaint();
        ComplaintService service = serviceAt(NOW);
        service.approveRefund(refund(complaintId, "50.00", null));

        Throwable second = catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", null)));

        assertErrorCode(second, "REFUND_ALREADY_REQUESTED");
        assertThat(((ComplaintException) second).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refunds.requests()).hasSize(1);
        assertThat(refundRecords.count()).isEqualTo(1);
    }

    @Test
    void retriedRequestWithTheSameIdempotencyKeyReplaysWithoutRefundingAgain() {
        UUID complaintId = newComplaint();
        ComplaintService service = serviceAt(NOW);
        ComplaintRefund first = service.approveRefund(refund(complaintId, "50.00", "agent-req-1"));

        ComplaintRefund retried = service.approveRefund(refund(complaintId, "50.00", "agent-req-1"));

        assertThat(retried.getId()).isEqualTo(first.getId());
        assertThat(retried.getStatus()).isEqualTo(ComplaintRefundStatus.SUCCEEDED);
        assertThat(refunds.requests()).hasSize(1);
        // A different key is a different request: still refused, never a second refund.
        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", "agent-req-2"))),
                "REFUND_ALREADY_REQUESTED");
        assertThat(refunds.requests()).hasSize(1);
    }

    @Test
    void retryAfterRejectionReplaysTheFailureWithoutCallingOrAlertingAgain() {
        UUID complaintId = newComplaint();
        refunds.setNextResult(RefundResult.rejected("gateway declined"));
        ComplaintService service = serviceAt(NOW);
        service.approveRefund(refund(complaintId, "50.00", "agent-req-1"));

        // The complaint is now REFUND_FAILED, but a replay is answered before the state check.
        ComplaintRefund retried = service.approveRefund(refund(complaintId, "50.00", "agent-req-1"));

        assertThat(retried.getStatus()).isEqualTo(ComplaintRefundStatus.FAILED);
        assertThat(refunds.requests()).hasSize(1);
        assertThat(financeAlerts.alerts()).hasSize(1);
        assertThat(notifications.refundFailures()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithADifferentAmountIsRejected() {
        UUID complaintId = newComplaint();
        ComplaintService service = serviceAt(NOW);
        service.approveRefund(refund(complaintId, "50.00", "agent-req-1"));

        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "75.00", "agent-req-1"))),
                "IDEMPOTENCY_KEY_REUSED");
        assertThat(refunds.requests()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = ComplaintStatus.class, names = {"RESOLVED", "CLOSED", "REFUND_FAILED"})
    void refundIsRefusedOutsideTheRefundableStates(ComplaintStatus status) {
        UUID complaintId = newComplaint();
        complaints.findById(complaintId).orElseThrow().changeStatus(status);

        Throwable thrown = catchThrowable(
                () -> serviceAt(NOW).approveRefund(refund(complaintId, "50.00", null)));

        assertErrorCode(thrown, "REFUND_NOT_ALLOWED");
        assertThat(((ComplaintException) thrown).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refunds.requests()).isEmpty();
        assertThat(refundRecords.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ComplaintStatus.class, names = {"OPEN", "IN_PROGRESS", "ESCALATED", "DISPUTED"})
    void refundIsAllowedFromEveryActiveState(ComplaintStatus status) {
        UUID complaintId = newComplaint();
        complaints.findById(complaintId).orElseThrow().changeStatus(status);

        ComplaintRefund refund = serviceAt(NOW).approveRefund(refund(complaintId, "50.00", null));

        assertThat(refund.getStatus()).isEqualTo(ComplaintRefundStatus.SUCCEEDED);
    }

    @Test
    void concurrentApprovalCaughtByTheUniqueConstraintIsRefusedBeforeCallingThePort() {
        UUID complaintId = newComplaint();
        Complaint complaint = complaints.findById(complaintId).orElseThrow();
        // A racing approval has inserted its record, but this transaction's lookup cannot see it.
        InMemoryComplaintRefundRepository racing = new InMemoryComplaintRefundRepository() {
            @Override
            public Optional<ComplaintRefund> findByComplaintId(UUID id) {
                return Optional.empty();
            }
        };
        racing.save(ComplaintRefund.reserve(complaintId, complaint.getBookingId(),
                new BigDecimal("50.00"), null, APPROVER, null, NOW));
        ComplaintService service = new ComplaintService(complaints, racing, agents, notifications,
                refunds, settlements, financeAlerts, events, properties, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", null))),
                "REFUND_ALREADY_REQUESTED");
        assertThat(refunds.requests()).isEmpty();
    }

    @Test
    void portThrowingLeavesTheRefundPendingAlertsFinanceAndBlocksRetries() {
        UUID complaintId = newComplaint();
        refunds.failWith(new IllegalStateException("read timed out"));
        ComplaintService service = serviceAt(NOW);

        Throwable thrown = catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", "agent-req-1")));

        assertErrorCode(thrown, "REFUND_OUTCOME_UNKNOWN");
        assertThat(((ComplaintException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        ComplaintRefund pending = refundRecords.findByComplaintId(complaintId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ComplaintRefundStatus.PENDING);
        // Outcome unknown: the customer is not told it failed and the complaint is not moved.
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.OPEN);
        assertThat(notifications.refundFailures()).isEmpty();
        assertThat(financeAlerts.reasons()).singleElement().asString()
                .contains(pending.paymentIdempotencyKey());

        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", "agent-req-1"))),
                "REFUND_IN_PROGRESS");
        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", null))),
                "REFUND_ALREADY_REQUESTED");
        assertThat(refunds.requests()).hasSize(1);
    }

    @Test
    void successThatCannotBeRecordedAlertsFinanceAndStaysPending() {
        UUID complaintId = newComplaint();
        InMemoryComplaintRefundRepository failingOnSuccess = new InMemoryComplaintRefundRepository() {
            @Override
            public ComplaintRefund save(ComplaintRefund refund) {
                if (refund.getStatus() == ComplaintRefundStatus.SUCCEEDED) {
                    throw new IllegalStateException("database unavailable");
                }
                return super.save(refund);
            }
        };
        ComplaintService service = new ComplaintService(complaints, failingOnSuccess, agents,
                notifications, refunds, settlements, financeAlerts, events, properties, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.approveRefund(refund(complaintId, "50.00", null)))
                .hasMessageContaining("database unavailable");
        assertThat(financeAlerts.reasons()).singleElement().asString()
                .contains("stub_rf_ref").contains("not recorded");
        // The in-memory record was mutated before the failed save; a real rollback leaves it
        // PENDING. Either way a second approval is refused.
        assertErrorCode(catchThrowable(
                () -> service.approveRefund(refund(complaintId, "50.00", null))),
                "REFUND_ALREADY_REQUESTED");
        assertThat(refunds.requests()).hasSize(1);
    }

    @Test
    void rejectionArrivingAfterTheComplaintWasClosedDoesNotReopenIt() {
        UUID complaintId = newComplaint();
        RefundPort closesMeanwhile = (bookingId, id, amount, key) -> {
            serviceAt(NOW).closeComplaint(id, ComplaintStatus.CLOSED);
            return RefundResult.rejected("gateway declined");
        };
        ComplaintService service = new ComplaintService(complaints, refundRecords, agents,
                notifications, closesMeanwhile, settlements, financeAlerts, events, properties,
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));

        ComplaintRefund refund = service.approveRefund(refund(complaintId, "50.00", null));

        assertThat(refund.getStatus()).isEqualTo(ComplaintRefundStatus.FAILED);
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.CLOSED);
        assertThat(financeAlerts.alerts()).containsExactly(complaintId);
    }

    @Test
    void refundRejectsInvalidRequestsBeforeTouchingAnything() {
        UUID complaintId = newComplaint();
        ComplaintService service = serviceAt(NOW);

        assertErrorCode(catchThrowable(() -> service.approveRefund(
                refund(complaintId, "0", null))), "VALIDATION_ERROR");
        assertErrorCode(catchThrowable(() -> service.approveRefund(
                refund(complaintId, "10.001", null))), "VALIDATION_ERROR");
        assertErrorCode(catchThrowable(() -> service.approveRefund(new ApproveRefundCommand(
                complaintId, BigDecimal.TEN, null, null, null))), "VALIDATION_ERROR");
        assertErrorCode(catchThrowable(() -> service.approveRefund(
                refund(complaintId, "50.00", "k".repeat(65)))), "VALIDATION_ERROR");
        assertErrorCode(catchThrowable(() -> service.approveRefund(
                refund(UUID.randomUUID(), "50.00", null))), "COMPLAINT_NOT_FOUND");
        assertThat(refunds.requests()).isEmpty();
        assertThat(refundRecords.count()).isZero();
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

    // ---- Admin Portal list and update (19.2) ---------------------------------------------------

    @Test
    void adminSearchReturnsNewestFirstFilteredByStatusAndCaseInsensitiveTerm() {
        Complaint older = serviceAt(NOW).createComplaint(new CreateComplaintCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ComplaintCategory.DAMAGE,
                ServicePriority.STANDARD, "Scratched the FLOOR", List.of()));
        Complaint newer = serviceAt(NOW.plusSeconds(60)).createComplaint(command(ServicePriority.STANDARD));
        serviceAt(NOW).changeStatus(newer.getId(), ComplaintStatus.IN_PROGRESS);

        assertThat(serviceAt(NOW).searchForAdmin(null, null))
                .extracting(Complaint::getId).containsExactly(newer.getId(), older.getId());
        assertThat(serviceAt(NOW).searchForAdmin("  ", ComplaintStatus.IN_PROGRESS))
                .extracting(Complaint::getId).containsExactly(newer.getId());
        assertThat(serviceAt(NOW).searchForAdmin("floor", null))
                .extracting(Complaint::getId).containsExactly(older.getId());
        String partialBooking = older.getBookingId().toString().substring(0, 8).toUpperCase();
        assertThat(serviceAt(NOW).searchForAdmin(partialBooking, ComplaintStatus.OPEN))
                .extracting(Complaint::getId).containsExactly(older.getId());
        // LIKE wildcards in the term are matched literally, not as "anything".
        assertThat(serviceAt(NOW).searchForAdmin("%", null)).isEmpty();
    }

    @Test
    void adminSearchIsBoundedByTheListLimit() {
        for (int i = 0; i < ComplaintService.ADMIN_LIST_LIMIT + 5; i++) {
            serviceAt(NOW.plusSeconds(i)).createComplaint(command(ServicePriority.STANDARD));
        }

        assertThat(serviceAt(NOW).searchForAdmin(null, null))
                .hasSize(ComplaintService.ADMIN_LIST_LIMIT);
    }

    @Test
    void adminResolveGoesThroughTheClosurePathAndRecordsTheNote() {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();
        serviceAt(NOW).markDisputed(complaintId);

        Complaint resolved = serviceAt(NOW.plusSeconds(30))
                .adminUpdate(complaintId, ComplaintStatus.RESOLVED, "  Refunded the visit fee. ");

        assertThat(resolved.getStatus()).isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(resolved.getResolutionNote()).isEqualTo("Refunded the visit fee.");
        // 16.8 hold release, the status event and the customer notification all happened.
        assertThat(settlements.releases()).containsExactly(complaintId);
        assertThat(events.statusChanges()).last()
                .extracting(RecordingComplaintEventPublisher.StatusChange::to)
                .isEqualTo(ComplaintStatus.RESOLVED);
        assertThat(notifications.statusChanges()).last().isEqualTo(ComplaintStatus.RESOLVED);
    }

    @Test
    void adminResolveWithoutANoteIsRejected() {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();

        Throwable thrown = catchThrowable(() ->
                serviceAt(NOW).adminUpdate(complaintId, ComplaintStatus.RESOLVED, " "));

        assertErrorCode(thrown, "VALIDATION_ERROR");
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.OPEN);
    }

    @Test
    void adminResendingTheCurrentStatusOnlyRecordsTheNote() {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();
        int eventsBefore = events.statusChanges().size();

        Complaint updated = serviceAt(NOW)
                .adminUpdate(complaintId, ComplaintStatus.OPEN, "Called the customer back.");

        assertThat(updated.getStatus()).isEqualTo(ComplaintStatus.OPEN);
        assertThat(updated.getResolutionNote()).isEqualTo("Called the customer back.");
        assertThat(events.statusChanges()).hasSize(eventsBefore);
        assertThat(notifications.statusChanges()).isEmpty();
    }

    @Test
    void adminCannotReopenATerminalComplaint() {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();
        serviceAt(NOW).closeComplaint(complaintId, ComplaintStatus.CLOSED);

        assertErrorCode(catchThrowable(() ->
                serviceAt(NOW).adminUpdate(complaintId, ComplaintStatus.IN_PROGRESS, null)),
                "INVALID_COMPLAINT_TRANSITION");
    }

    @ParameterizedTest
    @EnumSource(value = ComplaintStatus.class, names = {"ESCALATED", "REFUND_FAILED"})
    void adminCannotSetASystemOnlyState(ComplaintStatus status) {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();

        assertErrorCode(catchThrowable(() ->
                serviceAt(NOW).adminUpdate(complaintId, status, null)),
                "INVALID_COMPLAINT_TRANSITION");
        assertThat(complaints.findById(complaintId).orElseThrow().getStatus())
                .isEqualTo(ComplaintStatus.OPEN);
    }

    @Test
    void adminUpdateOfAnUnknownComplaintIsNotFound() {
        assertErrorCode(catchThrowable(() ->
                serviceAt(NOW).adminUpdate(UUID.randomUUID(), ComplaintStatus.IN_PROGRESS, null)),
                "COMPLAINT_NOT_FOUND");
    }

    @Test
    void reDisputingAComplaintWhoseHoldIsStillActiveRestoresDisputedWithoutASecondHold() {
        UUID complaintId = serviceAt(NOW).createComplaint(command(ServicePriority.STANDARD)).getId();
        serviceAt(NOW).markDisputed(complaintId);
        serviceAt(NOW).adminUpdate(complaintId, ComplaintStatus.IN_PROGRESS, null);

        Complaint disputed = serviceAt(NOW).adminUpdate(complaintId, ComplaintStatus.DISPUTED, null);

        assertThat(disputed.getStatus()).isEqualTo(ComplaintStatus.DISPUTED);
        assertThat(disputed.isSettlementHeld()).isTrue();
        assertThat(settlements.holds()).containsExactly(complaintId);
        assertThat(notifications.statusChanges()).last().isEqualTo(ComplaintStatus.DISPUTED);
    }
}
