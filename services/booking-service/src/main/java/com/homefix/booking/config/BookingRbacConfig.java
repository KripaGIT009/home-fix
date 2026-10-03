package com.homefix.booking.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Booking Service's read endpoints
 * (Requirement 19.6, 28.7), following the same registration pattern as the other services'
 * {@code *RbacConfig}.
 *
 * <p>Rules are registered in {@code @PostConstruct}, not on {@code ApplicationReadyEvent}: the
 * latter fires after the server is already accepting traffic, and until then the filter would see
 * an empty allow-list and — its documented behaviour for "no rule matches" — pass every request
 * through.
 *
 * <h2>Ordering and role semantics</h2>
 * <p>{@code RbacEnforcementFilter} takes the <strong>first matching entry</strong> in insertion
 * order, so {@code GET /bookings/history} is registered before the {@code GET /bookings/*} it
 * would otherwise fall under. Each role list is a logical OR. Role membership is not ownership:
 * which booking a caller may actually read is decided by {@code BookingQueryService}, which
 * answers 404 for a booking that is not theirs.
 *
 * <h2>Scope</h2>
 * <p>The read endpoints, the Admin Portal's {@code /admin/bookings/**} and the Tenant Portal's
 * {@code /tenant/bookings/**} (Requirement MT-10.1) have rules
 * ({@code /admin/bookings/**} also matches {@code /admin/bookings} itself). The command endpoints
 * ({@code POST /bookings/**}) remain "authenticated only", as before this class existed; their
 * ownership is enforced by {@code BookingAccess}, and giving them role rules is a separate change
 * with its own client-compatibility checks. {@code GET /bookings/*} matches a single path segment,
 * so it covers no command path and nothing deeper. {@code /internal/**} is guarded by the internal
 * credential instead, and the operational surface ({@code /health/**}, {@code /actuator/**},
 * {@code /metrics}, {@code /prometheus}) must stay unruled, because a rule there would turn the
 * unauthenticated probes into 401s.
 */
@Configuration
public class BookingRbacConfig {

    /** Staff roles that may read bookings on another user's behalf (support, dispatch, finance). */
    private static final List<String> STAFF = List.of(
            "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT", "DISPATCHER");

    /**
     * Service history is a customer feature. Staff are admitted and see their own (normally empty)
     * history — the endpoint never lists anybody else's — so admitting them is harmless and keeps
     * a staff account that also books services working. A provider-only account has no customer
     * history and is refused.
     */
    private static final List<String> HISTORY_TIER = withStaff("CUSTOMER");

    /** Booking detail: the customer, the assigned provider, and staff. */
    private static final List<String> DETAIL_TIER = withStaff("CUSTOMER", "SERVICE_PROVIDER");

    /**
     * Admin Portal Booking Management (Requirement 19.2): the list spans every customer and the
     * force-cancel acts on anyone's booking, so only the roles that run operations — admins,
     * support and dispatch — are admitted. Finance staff read bookings through the detail path and
     * have no cancel duty.
     */
    private static final List<String> ADMIN_TIER =
            List.of("ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT", "DISPATCHER");

    /**
     * The Tenant Portal's booking endpoints (Requirement MT-10.1): Tenant_Admins only. Platform
     * staff manage bookings through {@code /admin/bookings} and administer no Tenant, so admitting
     * them here would only produce a "not a Tenant admin" answer.
     */
    private static final List<String> TENANT_TIER = List.of("TENANT_ADMIN");

    private final RbacProperties rbacProperties;

    public BookingRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the per-endpoint role requirements. {@code public} so tests can drive it directly
     * against a plain {@link RbacProperties} instance without starting a Spring context.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("GET /bookings/history", HISTORY_TIER);
        rules.put("GET /bookings/*", DETAIL_TIER);
        rules.put("GET /admin/bookings/**", ADMIN_TIER);
        rules.put("POST /admin/bookings/**", ADMIN_TIER);
        rules.put("GET /tenant/bookings/**", TENANT_TIER);
        rules.put("POST /tenant/bookings/**", TENANT_TIER);
    }

    private static List<String> withStaff(String... roles) {
        return Stream.concat(Arrays.stream(roles), STAFF.stream()).toList();
    }
}
