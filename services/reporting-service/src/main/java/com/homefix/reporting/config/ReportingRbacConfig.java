package com.homefix.reporting.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Reporting Service (Requirement
 * 20.6).
 *
 * <p>Both report surfaces — the original {@code /reports/**} API and the Admin Portal's
 * {@code /admin/reports/**} adapter over it — are gated to the Admin tier (ADMIN, SUPER_ADMIN, or
 * FINANCE_ADMIN) by the shared {@code RbacEnforcementFilter}. The finer "only Finance_Admin may
 * run Payment Reconciliation / Settlement reports" rule cannot be expressed by the coarse
 * allow-list (which is a logical OR across the surface), so it is enforced per-request by
 * {@code ReportAuthorization} in the controllers as the authoritative check, with this allow-list
 * providing the surrounding authentication gate.
 *
 * <p>The rules are registered in {@code @PostConstruct}, during context refresh and so before the
 * server accepts traffic. They used to be registered on {@code ApplicationReadyEvent}, which left
 * a window after the web server was already serving in which the filter found no rule and passed
 * every request — any authenticated user, customers included — straight through.
 */
@Configuration
public class ReportingRbacConfig {

    private static final List<String> REPORT_TIER = List.of("ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN");

    private final RbacProperties rbacProperties;

    public ReportingRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("GET /reports/**", REPORT_TIER);
        rules.put("POST /reports/**", REPORT_TIER);
        // Admin Portal adapter (AdminReportController); "/admin/reports/**" also matches "/admin/reports".
        rules.put("GET /admin/reports/**", REPORT_TIER);
        rules.put("POST /admin/reports/**", REPORT_TIER);
    }
}
