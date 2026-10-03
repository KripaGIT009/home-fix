package com.homefix.auth.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Unit tests for {@link DevAccountSeeder}: the documented local accounts are created with their
 * roles and credentials, including the demo Tenant administrator {@code tenantadmin} that
 * docker/seed-tenants.sql links to the demo Tenant by mobile number (Requirement MT-15.5), an
 * existing account is refreshed rather than duplicated, and nothing is seeded without a password.
 */
@ExtendWith(MockitoExtension.class)
class DevAccountSeederTest {

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DevAccountSeeder seeder(String password) {
        DevSeedProperties properties = new DevSeedProperties();
        properties.setEnabled(true);
        properties.setPassword(password);
        return new DevAccountSeeder(userRepository, passwordEncoder, properties);
    }

    @Test
    void createsEveryAccount_includingTheTenantAdministrator() {
        when(passwordEncoder.encode("dev-pass")).thenReturn("$2a$12$hash");
        when(userRepository.findByMobileNumber(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        seeder("dev-pass").run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository, org.mockito.Mockito.times(10)).save(saved.capture());
        Map<String, UserAccount> byUsername = saved.getAllValues().stream()
                .collect(Collectors.toMap(UserAccount::getUsername, Function.identity()));
        UserAccount tenantAdmin = byUsername.get("tenantadmin");
        assertThat(tenantAdmin).isNotNull();
        assertThat(tenantAdmin.getMobileNumber()).isEqualTo("+919000000031");
        assertThat(tenantAdmin.getRoles()).containsExactly(Role.TENANT_ADMIN);
        assertThat(tenantAdmin.getPasswordHash()).isEqualTo("$2a$12$hash");
        assertThat(byUsername.get("superadmin").getRoles())
                .containsExactlyInAnyOrder(Role.SUPER_ADMIN, Role.ADMIN);
    }

    @Test
    void existingAccount_keepsItsIdAndGainsTheMissingRole() {
        when(passwordEncoder.encode("dev-pass")).thenReturn("$2a$12$hash");
        UserAccount existing = UserAccount.createVerified("+919000000031", Role.CUSTOMER);
        when(userRepository.findByMobileNumber(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByMobileNumber("+919000000031")).thenReturn(Optional.of(existing));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        seeder("dev-pass").run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository, org.mockito.Mockito.times(10)).save(saved.capture());
        List<UserAccount> tenantAdmins = saved.getAllValues().stream()
                .filter(a -> "tenantadmin".equals(a.getUsername())).toList();
        assertThat(tenantAdmins).singleElement().isSameAs(existing);
        assertThat(existing.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.TENANT_ADMIN);
    }

    @Test
    void withoutAPassword_seedsNothing() {
        seeder(" ").run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }
}
