package com.homefix.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.homefix.auth.api.UserNotFoundException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenService;

/**
 * Unit tests for {@link AdminUserService} (Requirement 19.2): the bounded, escaped search, and the
 * status-change guard rails (no self-change; SUPER_ADMIN for administrator accounts; 404 for an
 * unknown id), and the session revocation that follows a suspension.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final StaffActor ADMIN = new StaffActor(UUID.randomUUID(), Set.of("ADMIN"));
    private static final StaffActor SUPER_ADMIN = new StaffActor(UUID.randomUUID(), Set.of("SUPER_ADMIN"));

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private TokenService tokenService;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, tokenService);
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private UserAccount stubAccount(Role role) {
        UserAccount account = UserAccount.createVerified("+919000000001", role);
        lenient().when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    // ----- search -----

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankSearch_listsNewestFirstCappedAt200(String search) {
        List<UserAccount> all = List.of(UserAccount.createSocial(Role.CUSTOMER));
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findByOrderByCreatedAtDesc(page.capture())).thenReturn(all);

        assertThat(service.search(search)).isSameAs(all);
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        verify(userRepository, never()).searchForAdmin(anyString(), any());
    }

    @Test
    void nullSearch_listsEverything() {
        when(userRepository.findByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        assertThat(service.search(null)).isEmpty();
    }

    @Test
    void search_isATrimmedLowerCaseSubstringPatternCappedAt200() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.searchForAdmin(eq("%ops.lead%"), page.capture())).thenReturn(List.of());

        service.search("  Ops.Lead ");

        assertThat(page.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void search_escapesLikeWildcardsSoTheyMatchLiterally() {
        when(userRepository.searchForAdmin(any(), any())).thenReturn(List.of());

        service.search("50%_a\\b");

        verify(userRepository).searchForAdmin(eq("%50\\%\\_a\\\\b%"), any());
    }

    // ----- status change: happy paths -----

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void disablingAnAccount_savesTheStatusThenRevokesItsSessions(AccountStatus status) {
        UserAccount customer = stubAccount(Role.CUSTOMER);

        UserAccount updated = service.changeStatus(customer.getId(), status, ADMIN);

        assertThat(updated.getStatus()).isEqualTo(status);
        // Saved first, so a sign-in after the revocation always sees the new status.
        InOrder order = inOrder(userRepository, tokenService);
        order.verify(userRepository).save(customer);
        order.verify(tokenService).revokeAllRefreshTokens(customer.getId().toString());
    }

    @Test
    void reactivatingAnAccount_savesWithoutTouchingSessions() {
        UserAccount customer = stubAccount(Role.CUSTOMER);
        customer.changeStatus(AccountStatus.SUSPENDED);

        UserAccount updated = service.changeStatus(customer.getId(), AccountStatus.ACTIVE, ADMIN);

        assertThat(updated.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository).save(customer);
        verifyNoInteractions(tokenService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void superAdminMayChangeAnAdministratorsStatus(Role role) {
        UserAccount admin = stubAccount(role);

        assertThat(service.changeStatus(admin.getId(), AccountStatus.SUSPENDED, SUPER_ADMIN).getStatus())
                .isEqualTo(AccountStatus.SUSPENDED);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN",
            "DISPATCHER", "SUPPORT_AGENT", "TENANT_ADMIN"})
    void adminMayChangeANonAdministratorsStatus(Role role) {
        UserAccount account = stubAccount(role);

        assertThat(service.changeStatus(account.getId(), AccountStatus.SUSPENDED, ADMIN).getStatus())
                .isEqualTo(AccountStatus.SUSPENDED);
    }

    // ----- status change: guard rails -----

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void adminMayNotChangeAnAdministratorsStatus(Role role) {
        UserAccount target = stubAccount(Role.CUSTOMER);
        target.addRole(role);

        assertThatThrownBy(() -> service.changeStatus(target.getId(), AccountStatus.SUSPENDED, ADMIN))
                .isInstanceOf(AdminUserException.class)
                .satisfies(ex -> {
                    AdminUserException aue = (AdminUserException) ex;
                    assertThat(aue.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(aue.getErrorCode()).isEqualTo("SUPER_ADMIN_REQUIRED");
                });
        assertThat(target.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(tokenService);
    }

    @Test
    void tenantAdminWhoIsAlsoAPlatformAdmin_stillNeedsASuperAdmin() {
        // TENANT_ADMIN alone is not an administrator role (Requirement MT-12.3), but it must not
        // dilute one the account also holds.
        UserAccount target = stubAccount(Role.TENANT_ADMIN);
        target.addRole(Role.ADMIN);

        assertThatThrownBy(() -> service.changeStatus(target.getId(), AccountStatus.SUSPENDED, ADMIN))
                .isInstanceOf(AdminUserException.class)
                .satisfies(ex -> assertThat(((AdminUserException) ex).getErrorCode())
                        .isEqualTo("SUPER_ADMIN_REQUIRED"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void nobodyMayChangeTheirOwnStatus_evenASuperAdmin() {
        UserAccount self = stubAccount(Role.SUPER_ADMIN);
        StaffActor actor = new StaffActor(self.getId(), Set.of("SUPER_ADMIN"));

        assertThatThrownBy(() -> service.changeStatus(self.getId(), AccountStatus.SUSPENDED, actor))
                .isInstanceOf(AdminUserException.class)
                .satisfies(ex -> assertThat(((AdminUserException) ex).getErrorCode())
                        .isEqualTo("SELF_STATUS_CHANGE_FORBIDDEN"));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(tokenService);
    }

    @Test
    void unknownAccount_isUserNotFound() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(unknown, AccountStatus.SUSPENDED, SUPER_ADMIN))
                .isInstanceOf(UserNotFoundException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void staffCannotSetAnAccountToPendingVerification() {
        UserAccount account = stubAccount(Role.CUSTOMER);

        assertThatThrownBy(() -> service.changeStatus(account.getId(), AccountStatus.PENDING_VERIFICATION, SUPER_ADMIN))
                .isInstanceOf(AdminUserException.class)
                .extracting("errorCode").isEqualTo("STATUS_NOT_SETTABLE");
        verify(userRepository, never()).save(any());
    }
}
