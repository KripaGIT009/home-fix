package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.provider.catalog.CatalogClientPort;
import com.homefix.provider.domain.ProviderProfileRepository;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.TenantAdminRepository;
import com.homefix.provider.domain.TenantRepository;
import com.homefix.provider.domain.TenantStatus;
import com.homefix.provider.support.FakeAuthUserClient;

/**
 * Agency applications against a real database (email-auth Requirement 5): one open application per
 * user, pending applications that cover nothing and grant nothing (Property EA4), approval that makes
 * the applicant the administrator, rejection with a reason, and decision emails.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = TenantServiceJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:provider_applications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TenantApplicationJpaTest {

    private static final double LAT = 25.5560;
    private static final double LON = 84.6603;

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private TenantAdminRepository admins;
    @Autowired
    private ProviderProfileRepository profiles;

    private final UUID platformAdmin = UUID.randomUUID();
    private final UUID applicant = UUID.randomUUID();
    private final UUID plumbing = UUID.randomUUID();

    private RecordingAuth auth;
    private TenantService tenantService;
    private TenantApplicationService applications;

    /** The fake Auth Service, also keeping the decision emails asked for. */
    static final class RecordingAuth extends FakeAuthUserClient {
        final List<String> decisions = new ArrayList<>();

        @Override
        public void sendAgencyDecision(UUID userId, String tenantName, boolean approved, String reason) {
            decisions.add(userId + " " + tenantName + " " + (approved ? "approved" : "rejected: " + reason));
        }
    }

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        admins.deleteAll();
        tenants.deleteAll();
        auth = new RecordingAuth();
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isCategoryActive(UUID categoryId) {
                return true;
            }

            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }
        };
        tenantService = new TenantService(tenants, admins, profiles, catalog, auth);
        applications = new TenantApplicationService(tenants, admins, tenantService, auth);
    }

    private TenantCommand agency(String name) {
        return new TenantCommand(name, "+919000000041", "hello@agency.example", LAT, LON,
                new BigDecimal("12"), List.of(plumbing), "ACTIVE");
    }

    private static void assertRefused(Runnable call, HttpStatus status, String errorCode) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProviderException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(errorCode);
        });
    }

    @Test
    void anApplication_isPending_coversNothing_andGrantsNothing() {
        Tenant tenant = applications.apply(applicant, agency("Patna Plumbers")).tenant();

        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.PENDING_APPROVAL);
        assertThat(tenant.getApplicantUserId()).isEqualTo(applicant);
        assertThat(tenantService.covering(LAT, LON, plumbing)).isEmpty();
        assertThat(auth.callsSnapshot()).isEmpty();
        assertThat(admins.findById(applicant)).isEmpty();
        assertThat(applications.mine(applicant).getId()).isEqualTo(tenant.getId());
    }

    @Test
    void aUser_hasAtMostOneOpenApplication() {
        applications.apply(applicant, agency("First"));

        assertRefused(() -> applications.apply(applicant, agency("Second")), HttpStatus.CONFLICT,
                "APPLICATION_EXISTS");
    }

    @Test
    void approval_makesTheApplicantItsAdministrator_andTheAgencyCovers() {
        UUID tenantId = applications.apply(applicant, agency("Patna Plumbers")).tenant().getId();

        applications.approve(tenantId, platformAdmin);

        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(admins.findById(applicant)).get().extracting(a -> a.getTenantId()).isEqualTo(tenantId);
        assertThat(auth.callsSnapshot()).containsExactly("grant " + applicant);
        assertThat(tenantService.covering(LAT, LON, plumbing)).extracting(c -> c.tenantId()).containsExactly(tenantId);
        assertThat(auth.decisions).containsExactly(applicant + " Patna Plumbers approved");
        // Approved is "active": no second application.
        assertRefused(() -> applications.apply(applicant, agency("Again")), HttpStatus.CONFLICT, "APPLICATION_EXISTS");
    }

    @Test
    void approval_whenTheGrantFails_leavesTheApplicationPending() {
        UUID tenantId = applications.apply(applicant, agency("Patna Plumbers")).tenant().getId();
        auth.grantFailure = new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "down");

        assertRefused(() -> applications.approve(tenantId, platformAdmin), HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE");
        assertThat(tenants.findById(tenantId).orElseThrow().getStatus()).isEqualTo(TenantStatus.PENDING_APPROVAL);
        assertThat(admins.findById(applicant)).isEmpty();
        assertThat(auth.decisions).isEmpty();
    }

    @Test
    void rejection_recordsTheReason_andTheApplicantMayApplyAgain() {
        UUID tenantId = applications.apply(applicant, agency("Patna Plumbers")).tenant().getId();

        assertRefused(() -> applications.reject(tenantId, "  ", platformAdmin), HttpStatus.BAD_REQUEST,
                "REASON_REQUIRED");
        applications.reject(tenantId, "Service area is outside our launch cities", platformAdmin);

        Tenant tenant = applications.mine(applicant);
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.REJECTED);
        assertThat(tenant.getRejectionReason()).isEqualTo("Service area is outside our launch cities");
        assertThat(auth.decisions).containsExactly(
                applicant + " Patna Plumbers rejected: Service area is outside our launch cities");
        assertThat(auth.callsSnapshot()).isEmpty();
        assertRefused(() -> applications.approve(tenantId, platformAdmin), HttpStatus.CONFLICT,
                "APPLICATION_NOT_PENDING");

        Tenant second = applications.apply(applicant, agency("Patna Plumbers Ltd")).tenant();
        assertThat(applications.mine(applicant).getId()).isEqualTo(second.getId());
    }

    @Test
    void aPendingApplication_cannotBeActivatedOrStaffedByAnEdit() {
        UUID tenantId = applications.apply(applicant, agency("Patna Plumbers")).tenant().getId();
        auth.register(UUID.randomUUID(), "+919000000099");

        assertRefused(() -> tenantService.update(tenantId, agency("Patna Plumbers"), platformAdmin),
                HttpStatus.CONFLICT, "APPLICATION_NOT_DECIDED");
        assertRefused(() -> tenantService.addAdmin(tenantId, "+919000000099", platformAdmin),
                HttpStatus.CONFLICT, "APPLICATION_NOT_DECIDED");
        assertThat(applications.pending()).extracting(v -> v.tenant().getId()).containsExactly(tenantId);
    }

    @Test
    void aTenantCreatedByAPlatformAdmin_isNotAnApplication() {
        UUID tenantId = tenantService.create(agency("Direct Agency"), platformAdmin).tenant().getId();

        assertRefused(() -> applications.approve(tenantId, platformAdmin), HttpStatus.CONFLICT,
                "APPLICATION_NOT_PENDING");
    }
}
