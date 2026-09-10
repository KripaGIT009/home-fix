package com.homefix.reporting.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.homefix.reporting.domain.ReportType;

/**
 * Unit tests for the Finance_Admin restriction (Requirement 20.6): only a FINANCE_ADMIN may run
 * Payment Reconciliation and Settlement reports; every other role and report is unaffected.
 */
class ReportAuthorizationTest {

    private final ReportAuthorization authorization = new ReportAuthorization();

    private static UsernamePasswordAuthenticationToken auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("11111111-1111-1111-1111-111111111111",
                null, authorities);
    }

    @ParameterizedTest
    @EnumSource(value = ReportType.class, names = {"PAYMENT_RECONCILIATION", "SETTLEMENT"})
    void nonFinanceAdminIsDeniedRestrictedReports(ReportType restricted) {
        assertThat(authorization.canAccess(Set.of("ADMIN"), restricted)).isFalse();
        assertThat(authorization.canAccess(Set.of("SUPER_ADMIN"), restricted)).isFalse();

        assertThatThrownBy(() -> authorization.requireAccess(auth("ADMIN"), restricted))
                .isInstanceOf(ReportAccessDeniedException.class)
                .satisfies(ex -> assertThat(((ReportAccessDeniedException) ex).getReportType())
                        .isEqualTo(restricted));
    }

    @ParameterizedTest
    @EnumSource(value = ReportType.class, names = {"PAYMENT_RECONCILIATION", "SETTLEMENT"})
    void financeAdminCanAccessRestrictedReports(ReportType restricted) {
        assertThat(authorization.canAccess(Set.of("FINANCE_ADMIN"), restricted)).isTrue();
        // Should not throw.
        authorization.requireAccess(auth("FINANCE_ADMIN"), restricted);
    }

    @ParameterizedTest
    @EnumSource(value = ReportType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"PAYMENT_RECONCILIATION", "SETTLEMENT"})
    void anyAdminTierRoleCanAccessUnrestrictedReports(ReportType unrestricted) {
        assertThat(authorization.canAccess(Set.of("ADMIN"), unrestricted)).isTrue();
        assertThat(authorization.canAccess(Set.of("SUPER_ADMIN"), unrestricted)).isTrue();
        assertThat(authorization.canAccess(Set.of("FINANCE_ADMIN"), unrestricted)).isTrue();
        authorization.requireAccess(auth("ADMIN"), unrestricted);
    }

    @Test
    void unauthenticatedPrincipalIsDeniedRestrictedReports() {
        assertThat(authorization.canAccess(Set.of(), ReportType.SETTLEMENT)).isFalse();
        assertThatThrownBy(() ->
                authorization.requireAccess(null, ReportType.PAYMENT_RECONCILIATION))
                .isInstanceOf(ReportAccessDeniedException.class);
    }
}
