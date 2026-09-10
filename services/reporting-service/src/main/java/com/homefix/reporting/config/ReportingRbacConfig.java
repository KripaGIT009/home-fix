package com.homefix.reporting.config;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.homefix.shared.security.RbacProperties;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Reporting Service (Requirement
 * 20.6).
 *
 * <p>The whole {@code /reports/**} surface is gated to the Admin tier (ADMIN, SUPER_ADMIN, or
 * FINANCE_ADMIN) by the shared {@code RbacEnforcementFilter}. The finer "only Finance_Admin may
 * run Payment Reconciliation / Settlement reports" rule cannot be expressed by the coarse
 * allow-list (which is a logical OR across the surface), so it is enforced per-request by
 * {@code ReportAuthorization} in the controller as the authoritative check, with this allow-list
 * providing the surrounding authentication gate.
 */
@Configuration
public class ReportingRbacConfig {

    private static final List<String> REPORT_TIER = List.of("ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN");

    private final RbacProperties rbacProperties;

    public ReportingRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("GET /reports/**", REPORT_TIER);
        rules.put("POST /reports/**", REPORT_TIER);
    }
}
