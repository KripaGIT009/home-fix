package com.homefix.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

/**
 * The two projections behind the Admin Portal views against a real database (H2 in PostgreSQL
 * mode, schema generated from the mapping), so the grouped / constructor-expression JPQL is parsed
 * and executed rather than assumed: {@link VerificationRepository#findQueue} (Requirement 19.3) and
 * {@link VerificationRepository#findStatusesByProviderIds} (Requirement 19.2). Each must be a
 * single statement however many rows it returns — no aggregate or collection is loaded.
 */
@DataJpaTest
@ContextConfiguration(classes = AdminQueueQueriesJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:verification_admin_queue;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminQueueQueriesJpaTest {

    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private VerificationRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void queue_isTheSubmittedProvidersOldestFirstWithTheirDocumentCountInOneStatement()
            throws InterruptedException {
        UUID first = persistSubmitted(2);
        Thread.sleep(5); // distinct upload timestamps so the order is meaningful
        UUID second = persistSubmitted(3);
        Thread.sleep(5);
        UUID third = persistSubmitted(1);
        persistApproved();

        Statistics statistics = statistics();
        List<VerificationQueueRow> queue =
                repository.findQueue(VerificationStatus.DOCUMENT_SUBMITTED, PageRequest.of(0, 200));

        assertThat(queue).extracting(VerificationQueueRow::providerId).containsExactly(first, second, third);
        assertThat(queue).extracting(VerificationQueueRow::documentCount).containsExactly(2L, 3L, 1L);
        assertThat(queue).allSatisfy(row -> assertThat(row.lastUploadedAt()).isNotNull());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void queue_isBoundedByThePage() {
        persistSubmitted(1);
        persistSubmitted(1);
        persistSubmitted(1);

        assertThat(repository.findQueue(VerificationStatus.DOCUMENT_SUBMITTED, PageRequest.of(0, 2)))
                .hasSize(2);
    }

    @Test
    void queue_withoutDocumentRows_fallsBackToUpdatedAt() {
        UUID providerId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Verification v = Verification.create(providerId);
            v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
            repository.save(v);
        });

        List<VerificationQueueRow> queue =
                repository.findQueue(VerificationStatus.DOCUMENT_SUBMITTED, PageRequest.of(0, 200));

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).documentCount()).isZero();
        assertThat(queue.get(0).lastUploadedAt()).isNull();
        assertThat(queue.get(0).submittedAt()).isEqualTo(queue.get(0).updatedAt());
    }

    @Test
    void statuses_areReturnedForKnownIdsOnlyInOneStatement() {
        UUID submitted = persistSubmitted(1);
        UUID approved = persistApproved();
        UUID unknown = UUID.randomUUID();

        Statistics statistics = statistics();
        List<VerificationStatusView> views =
                repository.findStatusesByProviderIds(List.of(submitted, approved, unknown));

        assertThat(views).extracting(VerificationStatusView::providerId, VerificationStatusView::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(submitted, VerificationStatus.DOCUMENT_SUBMITTED),
                        org.assertj.core.groups.Tuple.tuple(approved, VerificationStatus.APPROVED));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    private UUID persistSubmitted(int documents) {
        UUID providerId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Verification v = Verification.create(providerId);
            for (int i = 0; i < documents; i++) {
                v.addDocument(DocumentType.values()[i % DocumentType.values().length],
                        "s3://bucket/" + providerId + "/" + i, "application/pdf", 10);
            }
            v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
            repository.save(v);
        });
        return providerId;
    }

    private UUID persistApproved() {
        UUID providerId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Verification v = Verification.create(providerId);
            v.addDocument(DocumentType.GOVERNMENT_ID, "s3://bucket/" + providerId, "image/png", 10);
            v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
            v.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, ADMIN, "verified");
            v.transitionTo(VerificationStatus.BACKGROUND_CHECK_PENDING, ADMIN, "check started");
            v.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, ADMIN, "check completed");
            v.transitionTo(VerificationStatus.APPROVED, ADMIN, "approved");
            repository.save(v);
        });
        return providerId;
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Verification.class)
    @EnableJpaRepositories(basePackageClasses = VerificationRepository.class)
    static class JpaConfig {
    }
}
