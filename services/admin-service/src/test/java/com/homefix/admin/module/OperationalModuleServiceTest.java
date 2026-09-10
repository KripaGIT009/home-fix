package com.homefix.admin.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AdminAction;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;
import com.homefix.admin.rbac.ModuleAccessDeniedException;
import com.homefix.admin.support.InMemoryAuditLogStore;

/**
 * Unit tests for {@link OperationalModuleService} proving that every permitted Admin action yields
 * exactly one Audit_Log entry (Requirement 19.8), and that an unauthorised action (ADMIN against
 * System Configuration) is rejected with no state change and no audit entry (Requirement 19.7).
 */
class OperationalModuleServiceTest {

    private InMemoryAuditLogStore auditStore;
    private OperationalModuleService service;

    private static Authentication auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("33333333-3333-3333-3333-333333333333",
                null, authorities);
    }

    @BeforeEach
    void setUp() {
        auditStore = new InMemoryAuditLogStore();
        AuditLogService auditLog = new AuditLogService(auditStore, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        service = new OperationalModuleService(
                new StubModuleActionAdapter(), new AdminAuthorization(), auditLog);
    }

    @Test
    void createProducesOneCreateAuditEntry() {
        service.create(auth("ADMIN"), AdminModule.COUPON_MANAGEMENT, "COUPON", "SAVE10",
                Map.of("discount", 10));

        assertThat(auditStore.all()).hasSize(1);
        assertThat(auditStore.all().get(0).getActionType()).isEqualTo(AdminAction.CREATE);
    }

    @Test
    void updateProducesOneUpdateAuditEntry() {
        service.update(auth("ADMIN"), AdminModule.USER_MANAGEMENT, "USER", "u-1",
                Map.of("status", "SUSPENDED"));

        assertThat(auditStore.all()).hasSize(1);
        assertThat(auditStore.all().get(0).getActionType()).isEqualTo(AdminAction.UPDATE);
    }

    @Test
    void deleteProducesOneDeleteAuditEntry() {
        service.delete(auth("ADMIN"), AdminModule.REVIEW_MODERATION, "REVIEW", "r-1");

        assertThat(auditStore.all()).hasSize(1);
        assertThat(auditStore.all().get(0).getActionType()).isEqualTo(AdminAction.DELETE);
    }

    @Test
    void approveAndRejectEachProduceAnAuditEntry() {
        service.approve(auth("ADMIN"), AdminModule.PROVIDER_MANAGEMENT, "PROVIDER", "p-1",
                Map.of("state", "APPROVED"));
        service.reject(auth("ADMIN"), AdminModule.PROVIDER_MANAGEMENT, "PROVIDER", "p-2",
                Map.of("state", "REJECTED"));

        assertThat(auditStore.all()).hasSize(2);
        assertThat(auditStore.all()).extracting(e -> e.getActionType())
                .containsExactly(AdminAction.APPROVE, AdminAction.REJECT);
    }

    @Test
    void everyPermittedActionYieldsExactlyOneAuditEntry() {
        int before = auditStore.all().size();
        service.update(auth("SUPER_ADMIN"), AdminModule.PRICING_CONFIGURATION, "PRICING_CONFIG",
                "GLOBAL", Map.of("platformFeePercent", 12));
        assertThat(auditStore.all()).hasSize(before + 1);
    }

    @Test
    void adminOnSystemConfigurationIsRejectedWithNoAuditEntry() {
        assertThatThrownBy(() -> service.update(auth("ADMIN"), AdminModule.SYSTEM_CONFIGURATION,
                "SYSTEM_CONFIG", "maintenanceMode", Map.of("value", "on")))
                .isInstanceOf(ModuleAccessDeniedException.class);

        // 403 short-circuits before any state change or audit write.
        assertThat(auditStore.all()).isEmpty();
    }

    @Test
    void superAdminOnSystemConfigurationSucceedsAndAudits() {
        service.update(auth("SUPER_ADMIN"), AdminModule.SYSTEM_CONFIGURATION,
                "SYSTEM_CONFIG", "maintenanceMode", Map.of("value", "on"));

        assertThat(auditStore.all()).hasSize(1);
        assertThat(auditStore.all().get(0).getActionType()).isEqualTo(AdminAction.UPDATE);
    }
}
