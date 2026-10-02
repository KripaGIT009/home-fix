package com.homefix.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homefix.verification.api.dto.VerificationResponse;
import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.service.DocumentUpload;
import com.homefix.verification.service.VerificationService;
import com.homefix.verification.storage.DocumentStoragePort;

import jakarta.persistence.EntityManagerFactory;

/**
 * The {@link Verification} aggregate's JPA mapping against a real database (CODEBASE_REVIEW 8.5:
 * "Two EAGER {@code List} bags on {@code Verification}", flagged with no JPA test to catch it).
 *
 * <p>The review expected a {@code MultipleBagFetchException} at boot; on this Hibernate (6.4) the
 * persistence unit does build, and the two eager bags are instead loaded with a secondary SELECT
 * each on every lookup — three statements and every child row even for the status-only dispatch
 * gate. Against the old mapping {@link #statusLookupLoadsNeitherCollection} and
 * {@link #eligibilityGateIsASingleStatement} fail (3 statements, collections initialised). The
 * tests pin the fetch plan: the status-only lookup touches neither collection, the full aggregate
 * costs a constant number of statements however many children it has, no child is duplicated by a
 * join, and the service returns an aggregate that can be mapped to a response <em>after</em> its
 * transaction has closed, as the controllers do with {@code open-in-view} off.
 *
 * <p>Runs on H2 in PostgreSQL mode. The ambient test transaction is disabled so each step commits
 * and closes its persistence context exactly as a request would.
 */
@DataJpaTest
@ContextConfiguration(classes = VerificationJpaMappingTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:verification_mapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerificationJpaMappingTest {

    private static final int DOCUMENTS = 4;
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private VerificationRepository repository;

    @Autowired
    private VerificationService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void statusLookupLoadsNeitherCollection() {
        UUID providerId = persistVerificationWithChildren();

        statistics.clear();
        Verification loaded = tx.execute(status -> repository.findByProviderId(providerId).orElseThrow());

        assertThat(Hibernate.isInitialized(loaded.getDocuments())).isFalse();
        assertThat(Hibernate.isInitialized(loaded.getAuditTrail())).isFalse();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void eligibilityGateIsASingleStatement() {
        UUID providerId = persistVerificationWithChildren();

        statistics.clear();
        boolean eligible = service.canReceiveJobAssignment(providerId);

        assertThat(eligible).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void documentGraphFetchJoinsDocumentsOnlyWithoutDuplicates() {
        UUID providerId = persistVerificationWithChildren();

        statistics.clear();
        Verification loaded = tx.execute(status ->
                repository.findWithDocumentsByProviderId(providerId).orElseThrow());

        assertThat(Hibernate.isInitialized(loaded.getDocuments())).isTrue();
        assertThat(loaded.getDocuments()).hasSize(DOCUMENTS);
        assertThat(Hibernate.isInitialized(loaded.getAuditTrail())).isFalse();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void fullAggregateReadIsTwoStatementsAndMapsAfterTheTransactionCloses() {
        UUID providerId = persistVerificationWithChildren();

        statistics.clear();
        Verification loaded = service.getByProviderId(providerId);
        long statements = statistics.getPrepareStatementCount();

        // Mapping happens here, outside any transaction, exactly as in the controllers. An
        // uninitialised collection would throw LazyInitializationException.
        VerificationResponse response = VerificationResponse.from(loaded);

        assertThat(statements).as("root + documents join, then audit trail").isEqualTo(2);
        assertThat(response.documents()).hasSize(DOCUMENTS);
        assertThat(response.auditTrail()).extracting(VerificationResponse.AuditEntryResponse::sequence)
                .containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(response.status()).isEqualTo(VerificationStatus.APPROVED.name());
    }

    @Test
    void everyMutationReturnsAnAggregateMappableOutsideTheTransaction() {
        UUID providerId = UUID.randomUUID();

        VerificationResponse submitted = VerificationResponse.from(
                service.submitDocuments(providerId, uploads(), providerId));
        VerificationResponse verified = VerificationResponse.from(
                service.markDocumentsVerified(providerId, ADMIN, null));
        VerificationResponse checked = VerificationResponse.from(
                service.completeBackgroundCheck(providerId, ADMIN, "clear"));
        VerificationResponse approved = VerificationResponse.from(service.approve(providerId, ADMIN, null));
        VerificationResponse suspended = VerificationResponse.from(service.suspend(providerId, ADMIN, null));
        VerificationResponse reinstated = VerificationResponse.from(service.approve(providerId, ADMIN, null));

        assertThat(submitted.documents()).hasSize(3);
        assertThat(submitted.auditTrail()).hasSize(1);
        assertThat(verified.auditTrail()).hasSize(3);
        assertThat(checked.backgroundCheckResult()).isEqualTo("clear");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(suspended.status()).isEqualTo("SUSPENDED");
        assertThat(reinstated.auditTrail()).hasSize(7);
        assertThat(reinstated.documents()).hasSize(3);
    }

    @Test
    void rejectReturnsAnAggregateMappableOutsideTheTransaction() {
        UUID providerId = UUID.randomUUID();
        service.submitDocuments(providerId, uploads(), providerId);

        VerificationResponse rejected = VerificationResponse.from(
                service.reject(providerId, ADMIN, "Illegible ID"));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.auditTrail()).last()
                .extracting(VerificationResponse.AuditEntryResponse::reason).isEqualTo("Illegible ID");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Persists an APPROVED verification with {@value #DOCUMENTS} documents and a five-entry audit
     * trail — enough children on both sides that a join of the two would visibly multiply rows.
     */
    private UUID persistVerificationWithChildren() {
        UUID providerId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            Verification v = Verification.create(providerId);
            for (int i = 0; i < DOCUMENTS; i++) {
                v.addDocument(DocumentType.GOVERNMENT_ID, "s3://bucket/" + providerId + "/" + i,
                        "application/pdf", 10L + i);
            }
            v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
            v.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, ADMIN, "verified");
            v.transitionTo(VerificationStatus.BACKGROUND_CHECK_PENDING, ADMIN, "check started");
            v.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, ADMIN, "check completed");
            v.transitionTo(VerificationStatus.APPROVED, ADMIN, "approved");
            repository.save(v);
        });
        return providerId;
    }

    private static List<DocumentUpload> uploads() {
        return List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1}, "application/pdf"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[] {2}, "application/pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[] {3}, "application/pdf"));
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Verification.class)
    @EnableJpaRepositories(basePackageClasses = VerificationRepository.class)
    static class JpaConfig {

        @Bean
        VerificationService verificationService(VerificationRepository repository) {
            DocumentStoragePort storage = (provider, type, content, contentType) ->
                    "s3://homefix-documents/" + provider + "/" + type + "/" + UUID.randomUUID();
            BackgroundCheckPort backgroundCheck = provider -> "check-" + provider;
            ProviderNotificationPort notification = (provider, reason) -> { };
            DispatchPoolPort dispatchPool = provider -> { };
            return new VerificationService(repository, storage, backgroundCheck, notification,
                    dispatchPool, new VerificationProperties());
        }
    }
}
