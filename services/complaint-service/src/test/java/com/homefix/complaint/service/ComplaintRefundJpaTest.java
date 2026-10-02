package com.homefix.complaint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homefix.complaint.config.ComplaintProperties;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ComplaintRefund;
import com.homefix.complaint.domain.ComplaintRefundRepository;
import com.homefix.complaint.domain.ComplaintRefundStatus;
import com.homefix.complaint.domain.ComplaintRepository;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.domain.ServicePriority;
import com.homefix.complaint.payment.RefundPort;
import com.homefix.complaint.payment.RefundResult;
import com.homefix.complaint.support.FixedSupportAgentDirectory;
import com.homefix.complaint.support.RecordingComplaintEventPublisher;
import com.homefix.complaint.support.RecordingCustomerNotificationPort;
import com.homefix.complaint.support.RecordingFinanceAlertPort;
import com.homefix.complaint.support.RecordingSettlementHoldPort;

/**
 * The refund flow (Requirement 16.5, 16.6) against a real database: the {@link ComplaintRefund}
 * mapping, the unique {@code complaint_id} constraint, the complaint row lock, and the three short
 * transactions of {@link ComplaintService#approveRefund}, on H2 in PostgreSQL mode with a real
 * {@link TransactionTemplate}. PostgreSQL itself is not exercised (no Docker in this build).
 *
 * <p>The ambient test transaction is disabled so every phase commits on its own, as in production.
 */
@DataJpaTest
@ContextConfiguration(classes = ComplaintRefundJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:complaint_refund;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ComplaintRefundJpaTest {

    private static final UUID APPROVER = UUID.randomUUID();

    @Autowired
    private ComplaintRepository complaints;

    @Autowired
    private ComplaintRefundRepository refunds;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private final RecordingFinanceAlertPort financeAlerts = new RecordingFinanceAlertPort();
    private final RecordingCustomerNotificationPort notifications =
            new RecordingCustomerNotificationPort();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ComplaintService service(RefundPort port) {
        return new ComplaintService(complaints, refunds,
                new FixedSupportAgentDirectory(UUID.randomUUID(), UUID.randomUUID()), notifications,
                port, new RecordingSettlementHoldPort(), financeAlerts,
                new RecordingComplaintEventPublisher(), new ComplaintProperties(), tx);
    }

    private Complaint persistedComplaint() {
        Instant now = Instant.now();
        Complaint complaint = Complaint.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ComplaintCategory.DAMAGE, ServicePriority.STANDARD,
                "The new tap leaks.", now, now.plus(Duration.ofHours(72)));
        return tx.execute(status -> complaints.save(complaint));
    }

    private static ApproveRefundCommand approve(UUID complaintId) {
        return new ApproveRefundCommand(complaintId, new BigDecimal("1250.00"), "tap leaked",
                APPROVER, null);
    }

    @Test
    void pendingRecordIsCommittedBeforeThePortIsCalledWithNoTransactionOpen() {
        Complaint complaint = persistedComplaint();
        List<Boolean> portInTransaction = new ArrayList<>();
        List<ComplaintRefundStatus> committedDuringCall = new ArrayList<>();
        RefundPort port = (bookingId, complaintId, amount, key) -> {
            portInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            // Read in a fresh transaction: only a committed row is visible here.
            committedDuringCall.add(new TransactionTemplate(transactionManager).execute(status ->
                    refunds.findByComplaintId(complaintId).orElseThrow().getStatus()));
            return RefundResult.approved("pay_rf_1");
        };

        ComplaintRefund refund = service(port).approveRefund(approve(complaint.getId()));

        assertThat(portInTransaction).containsExactly(false);
        assertThat(committedDuringCall).containsExactly(ComplaintRefundStatus.PENDING);
        ComplaintRefund stored = tx.execute(status ->
                refunds.findById(refund.getId()).orElseThrow());
        assertThat(stored.getStatus()).isEqualTo(ComplaintRefundStatus.SUCCEEDED);
        assertThat(stored.getExternalReference()).isEqualTo("pay_rf_1");
        assertThat(stored.getApprovedBy()).isEqualTo(APPROVER);
        assertThat(stored.getAmount()).isEqualByComparingTo("1250.00");
        assertThat(stored.getReason()).isEqualTo("tap leaked");
        assertThat(stored.getCompletedAt()).isNotNull();
    }

    @Test
    void rejectionIsPersistedAsFailedAndTheComplaintAsRefundFailed() {
        Complaint complaint = persistedComplaint();

        ComplaintRefund refund = service((b, c, a, k) -> RefundResult.rejected("card expired"))
                .approveRefund(approve(complaint.getId()));

        ComplaintRefund stored = tx.execute(status -> refunds.findById(refund.getId()).orElseThrow());
        assertThat(stored.getStatus()).isEqualTo(ComplaintRefundStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo("card expired");
        Complaint storedComplaint = tx.execute(status ->
                complaints.findById(complaint.getId()).orElseThrow());
        assertThat(storedComplaint.getStatus()).isEqualTo(ComplaintStatus.REFUND_FAILED);
        assertThat(financeAlerts.alerts()).containsExactly(complaint.getId());
    }

    @Test
    void uniqueConstraintRejectsASecondRefundRowForTheSameComplaint() {
        Complaint complaint = persistedComplaint();
        tx.executeWithoutResult(status -> refunds.save(ComplaintRefund.reserve(complaint.getId(),
                complaint.getBookingId(), BigDecimal.TEN, null, APPROVER, null, Instant.now())));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> refunds.save(
                ComplaintRefund.reserve(complaint.getId(), complaint.getBookingId(), BigDecimal.ONE,
                        null, APPROVER, null, Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentApprovalsOfTheSameComplaintRefundExactlyOnce() throws Exception {
        Complaint complaint = persistedComplaint();
        List<String> keysSent = new CopyOnWriteArrayList<>();
        RefundPort port = (bookingId, complaintId, amount, key) -> {
            keysSent.add(key);
            return RefundResult.approved("pay_rf_" + keysSent.size());
        };
        ComplaintService service = service(port);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(executor.submit(() -> {
                start.await();
                try {
                    return service.approveRefund(approve(complaint.getId()));
                } catch (ComplaintException e) {
                    return e;
                }
            }));
        }
        start.countDown();

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes).filteredOn(ComplaintRefund.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ComplaintException.class::isInstance).singleElement()
                .extracting(e -> ((ComplaintException) e).getErrorCode())
                .isEqualTo("REFUND_ALREADY_REQUESTED");
        assertThat(keysSent).hasSize(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Complaint.class)
    @EnableJpaRepositories(basePackageClasses = ComplaintRepository.class)
    static class JpaConfig {
    }
}
