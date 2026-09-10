package com.homefix.admin.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit tests for the Admin module RBAC rule (Requirement 19.6, 19.7): ADMIN can access every
 * module except System Configuration; SUPER_ADMIN can access all.
 */
class AdminAuthorizationTest {

    private final AdminAuthorization authorization = new AdminAuthorization();

    private static UsernamePasswordAuthenticationToken auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("11111111-1111-1111-1111-111111111111",
                null, authorities);
    }

    @Test
    void adminIsBlockedFromSystemConfiguration() {
        assertThatThrownBy(() ->
                authorization.requireAccess(auth("ADMIN"), AdminModule.SYSTEM_CONFIGURATION))
                .isInstanceOf(ModuleAccessDeniedException.class)
                .satisfies(ex -> assertThat(((ModuleAccessDeniedException) ex).getModule())
                        .isEqualTo(AdminModule.SYSTEM_CONFIGURATION));

        assertThat(authorization.canAccess(Set.of("ADMIN"), AdminModule.SYSTEM_CONFIGURATION))
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AdminModule.class, mode = EnumSource.Mode.EXCLUDE, names = "SYSTEM_CONFIGURATION")
    void adminCanAccessEveryModuleExceptSystemConfiguration(AdminModule module) {
        // Should not throw for any non-System-Configuration module.
        authorization.requireAccess(auth("ADMIN"), module);
        assertThat(authorization.canAccess(Set.of("ADMIN"), module)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AdminModule.class)
    void superAdminCanAccessAllModulesIncludingSystemConfiguration(AdminModule module) {
        authorization.requireAccess(auth("SUPER_ADMIN"), module);
        assertThat(authorization.canAccess(Set.of("SUPER_ADMIN"), module)).isTrue();
    }

    @Test
    void nonAdminRoleCannotAccessAnyModule() {
        assertThat(authorization.canAccess(Set.of("CUSTOMER"), AdminModule.USER_MANAGEMENT)).isFalse();
        assertThatThrownBy(() ->
                authorization.requireAccess(auth("CUSTOMER"), AdminModule.USER_MANAGEMENT))
                .isInstanceOf(ModuleAccessDeniedException.class);
    }

    @Test
    void unauthenticatedPrincipalHasNoAccess() {
        assertThat(authorization.canAccess(Set.of(), AdminModule.USER_MANAGEMENT)).isFalse();
        assertThatThrownBy(() ->
                authorization.requireAccess(null, AdminModule.USER_MANAGEMENT))
                .isInstanceOf(ModuleAccessDeniedException.class);
    }
}
