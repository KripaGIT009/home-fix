package com.homefix.complaint.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.homefix.complaint.agent.StubSupportAgentDirectoryAdapter;
import com.homefix.complaint.alert.LoggingFinanceAlertAdapter;
import com.homefix.complaint.api.dto.ComplaintResponse;
import com.homefix.complaint.api.dto.ComplaintStatsResponse;
import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.event.ComplaintCreatedEvent;
import com.homefix.complaint.event.ComplaintEventPublisher;
import com.homefix.complaint.event.ComplaintStatusChangedEvent;
import com.homefix.complaint.notification.LoggingCustomerNotificationAdapter;
import com.homefix.complaint.payment.LoggingRefundAdapter;
import com.homefix.complaint.payment.RefundResult;
import com.homefix.complaint.service.ComplaintException;
import com.homefix.complaint.service.ComplaintService;
import com.homefix.complaint.service.ComplaintSlaScheduler;
import com.homefix.complaint.service.ComplaintStats;
import com.homefix.complaint.settlement.LoggingSettlementHoldAdapter;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the Complaint Service adapters, outbox publisher seam, SLA scheduler, tunable
 * properties, response projections, and domain exception factories (Requirement 16).
 */
class ComplaintAdaptersConfigTest {

    private Complaint complaint() {
        return Complaint.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ComplaintCategory.POOR_QUALITY, ServicePriority.STANDARD, "bad", Instant.now(),
                Instant.now().plus(Duration.ofHours(72)));
    }

    @Test
    void loggingAdapters_doNotThrow() {
        LoggingCustomerNotificationAdapter notif = new LoggingCustomerNotificationAdapter();
        UUID cust = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        assertThatCode(() -> {
            notif.acknowledgeComplaint(cust, id);
            notif.notifyStatusChange(cust, id, ComplaintStatus.IN_PROGRESS);
            notif.notifyResolutionDelayed(cust, id);
            notif.notifyRefundFailed(cust, id);
        }).doesNotThrowAnyException();

        LoggingSettlementHoldAdapter hold = new LoggingSettlementHoldAdapter();
        assertThatCode(() -> {
            hold.placeHold(UUID.randomUUID(), UUID.randomUUID(), id);
            hold.releaseHold(UUID.randomUUID(), UUID.randomUUID(), id);
        }).doesNotThrowAnyException();

        new LoggingFinanceAlertAdapter().refundRequiresManualProcessing(
                id, UUID.randomUUID(), new BigDecimal("10.00"), "rejected");

        LoggingRefundAdapter refundAdapter = new LoggingRefundAdapter();
        RefundResult result = refundAdapter
                .requestRefund(UUID.randomUUID(), id, new BigDecimal("25.00"), "complaint-refund:k1");
        assertThat(result.approved()).isTrue();
        assertThat(result.transactionRef()).startsWith("stub_rf_");
        // Same key, same reference: the stub mirrors the Payment Service's idempotency.
        assertThat(refundAdapter.requestRefund(UUID.randomUUID(), id, new BigDecimal("25.00"),
                "complaint-refund:k1").transactionRef()).isEqualTo(result.transactionRef());
    }

    @Test
    void stubAgentDirectory_alwaysProvidesAnAgent() {
        StubSupportAgentDirectoryAdapter dir = new StubSupportAgentDirectoryAdapter();
        assertThat(dir.nextAvailableSupportAgent()).isPresent();
        assertThat(dir.nextAvailableSeniorSupportAgent()).isPresent();
    }

    @Test
    void refundResult_factories() {
        assertThat(RefundResult.approved("ref").approved()).isTrue();
        assertThat(RefundResult.rejected("why").approved()).isFalse();
        assertThat(RefundResult.rejected("why").failureReason()).isEqualTo("why");
    }

    @Test
    void eventPublisher_publishesCreatedAndStatusChanged() {
        OutboxEventPublisher outbox = mock(OutboxEventPublisher.class);
        ComplaintEventPublisher publisher = new ComplaintEventPublisher(outbox);
        Complaint c = complaint();

        publisher.publishCreated(c);
        verify(outbox).publish(eq(ComplaintCreatedEvent.AGGREGATE_TYPE), eq(c.getId()),
                eq(ComplaintCreatedEvent.EVENT_TYPE), any(ComplaintCreatedEvent.class));

        publisher.publishStatusChanged(c, ComplaintStatus.OPEN, Instant.now());
        verify(outbox).publish(eq(ComplaintStatusChangedEvent.AGGREGATE_TYPE), eq(c.getId()),
                eq(ComplaintStatusChangedEvent.EVENT_TYPE), any(ComplaintStatusChangedEvent.class));
    }

    @Test
    void slaScheduler_delegatesAndSwallowsFailures() {
        ComplaintService service = mock(ComplaintService.class);
        ComplaintSlaScheduler scheduler = new ComplaintSlaScheduler(service);
        when(service.aggregateStats()).thenReturn(new ComplaintStats(Map.of(), 0, 0, 0.0, Duration.ZERO));

        scheduler.sweepSlaBreaches();
        scheduler.refreshStats();
        verify(service).enforceSlaBreaches();
        verify(service).aggregateStats();

        // Exceptions from the sweep/refresh must be swallowed (retried next tick).
        doThrow(new RuntimeException("boom")).when(service).enforceSlaBreaches();
        when(service.aggregateStats()).thenThrow(new RuntimeException("boom"));
        assertThatCode(scheduler::sweepSlaBreaches).doesNotThrowAnyException();
        assertThatCode(scheduler::refreshStats).doesNotThrowAnyException();
    }

    @Test
    void complaintProperties_exposeDefaultsAndRoundTrip() {
        ComplaintProperties p = new ComplaintProperties();
        assertThat(p.getMaxDescriptionLength()).isEqualTo(2000);
        assertThat(p.getAcknowledgmentSla()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getStatusChangeNotificationSla()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.getEmergencyResolutionSla()).isEqualTo(Duration.ofHours(24));
        assertThat(p.getStandardResolutionSla()).isEqualTo(Duration.ofHours(72));
        assertThat(p.getStatsRefreshWindow()).isEqualTo(Duration.ofMinutes(60));
        assertThat(p.getAttachments().getMaxCount()).isEqualTo(5);
        assertThat(p.getAttachments().getMaxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(p.getTopics().getComplaintCreated()).isEqualTo("ComplaintCreated");
        assertThat(p.getTopics().getComplaintStatusChanged()).isEqualTo("ComplaintStatusChanged");

        p.setMaxDescriptionLength(500);
        p.setAcknowledgmentSla(Duration.ofMinutes(10));
        p.setStatusChangeNotificationSla(Duration.ofMinutes(2));
        p.setEmergencyResolutionSla(Duration.ofHours(12));
        p.setStandardResolutionSla(Duration.ofHours(48));
        p.setStatsRefreshWindow(Duration.ofMinutes(30));
        ComplaintProperties.Attachments a = new ComplaintProperties.Attachments();
        a.setMaxCount(3);
        a.setMaxBytes(1024L);
        p.setAttachments(a);
        ComplaintProperties.Topics t = new ComplaintProperties.Topics();
        t.setComplaintCreated("cc");
        t.setComplaintStatusChanged("csc");
        p.setTopics(t);

        assertThat(p.getMaxDescriptionLength()).isEqualTo(500);
        assertThat(p.getEmergencyResolutionSla()).isEqualTo(Duration.ofHours(12));
        assertThat(p.getAttachments().getMaxCount()).isEqualTo(3);
        assertThat(p.getTopics().getComplaintCreated()).isEqualTo("cc");
        assertThat(p.getTopics().getComplaintStatusChanged()).isEqualTo("csc");
    }

    @Test
    void complaintResponse_mapsAllFields() {
        Complaint c = complaint();
        ComplaintResponse response = ComplaintResponse.from(c);
        assertThat(response.id()).isEqualTo(c.getId());
        assertThat(response.category()).isEqualTo("POOR_QUALITY");
        assertThat(response.priority()).isEqualTo("STANDARD");
        assertThat(response.status()).isEqualTo(c.getStatus().name());
        assertThat(response.slaDeadline()).isEqualTo(c.getSlaDeadline());
    }

    @Test
    void complaintStatsResponse_projectsCategoryCountsAndSeconds() {
        ComplaintStats stats = new ComplaintStats(
                Map.of(ComplaintCategory.POOR_QUALITY, 2L), 4, 3, 0.75, Duration.ofSeconds(120));
        ComplaintStatsResponse response = ComplaintStatsResponse.from(stats);
        assertThat(response.totalComplaints()).isEqualTo(4);
        assertThat(response.resolvedComplaints()).isEqualTo(3);
        assertThat(response.resolutionRate()).isEqualTo(0.75);
        assertThat(response.averageResolutionSeconds()).isEqualTo(120);
        assertThat(response.categoryCounts()).containsEntry("POOR_QUALITY", 2L);
    }

    @Test
    void complaintException_factoriesCarryStatusAndErrorCode() {
        assertThat(ComplaintException.validation("x").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ComplaintException.notFound("x").getErrorCode()).isEqualTo("COMPLAINT_NOT_FOUND");
        assertThat(ComplaintException.noAgentAvailable("x").getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ComplaintException.invalidTransition("x").getErrorCode())
                .isEqualTo("INVALID_COMPLAINT_TRANSITION");
        assertThat(ComplaintException.validation("x", java.util.List.of("d")).getDetails())
                .containsExactly("d");
    }
}
