package com.homefix.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.service.VerificationService;
import com.homefix.verification.storage.DocumentStoragePort;

import jakarta.persistence.EntityManagerFactory;

/**
 * The batch {@code APPROVED} lookup behind {@code POST /internal/verifications/approved}
 * (Requirements 5.10, 8.2) against a real database: H2 in PostgreSQL mode, schema generated from
 * the mapping. Pins that it returns exactly the approved subset — not suspended, not in-flight,
 * not unknown ids — in a single statement however many ids are asked about.
 */
@DataJpaTest
@ContextConfiguration(classes = ApprovedProvidersLookupJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:verification_approved;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovedProvidersLookupJpaTest {

    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private VerificationRepository repository;

    @Autowired
    private VerificationService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void returnsOnlyApprovedProvidersInOneStatement() {
        UUID approved = persist(VerificationStatus.APPROVED);
        UUID alsoApproved = persist(VerificationStatus.APPROVED);
        UUID suspended = persist(VerificationStatus.SUSPENDED);
        UUID inFlight = persist(VerificationStatus.DOCUMENT_SUBMITTED);
        UUID unknown = UUID.randomUUID();
        UUID approvedButNotAsked = persist(VerificationStatus.APPROVED);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        Set<UUID> result = service.approvedAmong(
                List.of(approved, alsoApproved, suspended, inFlight, unknown, approved));

        assertThat(result).containsExactlyInAnyOrder(approved, alsoApproved);
        assertThat(result).doesNotContain(approvedButNotAsked);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void emptyQuestion_isAnsweredWithoutQuerying() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(service.approvedAmong(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    /** Persists a verification walked through the real state machine to {@code target}. */
    private UUID persist(VerificationStatus target) {
        UUID providerId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Verification v = Verification.create(providerId);
            v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
            if (target != VerificationStatus.DOCUMENT_SUBMITTED) {
                v.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, ADMIN, "verified");
                v.transitionTo(VerificationStatus.BACKGROUND_CHECK_PENDING, ADMIN, "check started");
                v.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, ADMIN, "check completed");
                v.transitionTo(VerificationStatus.APPROVED, ADMIN, "approved");
                if (target == VerificationStatus.SUSPENDED) {
                    v.transitionTo(VerificationStatus.SUSPENDED, ADMIN, "suspended");
                }
            }
            repository.save(v);
        });
        return providerId;
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Verification.class)
    @EnableJpaRepositories(basePackageClasses = VerificationRepository.class)
    static class JpaConfig {

        @Bean
        VerificationService verificationService(VerificationRepository repository) {
            DocumentStoragePort storage = (provider, type, content, contentType) -> "s3://unused";
            BackgroundCheckPort backgroundCheck = provider -> "check-" + provider;
            ProviderNotificationPort notification = (provider, reason) -> { };
            DispatchPoolPort dispatchPool = provider -> { };
            return new VerificationService(repository, storage, backgroundCheck, notification,
                    dispatchPool, new VerificationProperties());
        }
    }
}
