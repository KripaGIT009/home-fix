package com.homefix.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.homefix.auth.api.UserNotFoundException;
import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.auth.token.RefreshService;
import com.homefix.auth.token.TokenException;
import com.homefix.auth.token.TokenService;
import com.homefix.shared.security.SecurityProperties;

/**
 * Unit tests for {@link AccountRoleService} (Requirement MT-2.1, MT-2.2, MT-2.4, MT-2.5): lookup by
 * mobile, idempotent grant and revoke of TENANT_ADMIN only, and the effect on tokens. The token
 * service and {@link RefreshService} are real, over the in-memory refresh-token store, so "the next
 * refreshed token carries the role" and "revoking ends the sessions" are checked end to end; only
 * the account repository is mocked.
 */
@ExtendWith(MockitoExtension.class)
class AccountRoleServiceTest {

    private static final String PHONE = "+919000000031";

    @Mock
    private UserAccountRepository userRepository;

    private InMemoryRefreshTokenStore tokenStore;
    private TokenService tokenService;
    private RefreshService refreshService;
    private AccountRoleService service;

    @BeforeEach
    void setUp() {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret("unit-test-signing-secret-that-is-32b+");
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));
        tokenStore = new InMemoryRefreshTokenStore();
        tokenService = new TokenService(security, tokenProps, tokenStore);
        refreshService = new RefreshService(tokenService, userRepository);
        service = new AccountRoleService(userRepository, tokenService);
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.saveAndFlush(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private UserAccount stubAccount(Role role) {
        UserAccount account = UserAccount.createVerified(PHONE, role);
        lenient().when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private String signIn(UserAccount account) {
        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        return tokenService.issueTokens(account.getId().toString(), roles).refreshToken();
    }

    @SuppressWarnings("unchecked")
    private List<String> rolesIn(String accessToken) {
        return tokenService.parseAndVerify(accessToken).get(TokenService.ROLES_CLAIM, List.class);
    }

    // ----- lookup by mobile -----

    @Test
    void findByMobile_matchesTheStoredNumber() {
        UserAccount account = stubAccount(Role.CUSTOMER);
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(account));

        assertThat(service.findByMobile(PHONE)).contains(account);
    }

    @Test
    void findByMobile_readsAnUnencodedPlusThatArrivedAsASpace() {
        UserAccount account = stubAccount(Role.CUSTOMER);
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(account));

        assertThat(service.findByMobile(" 919000000031")).contains(account);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void findByMobile_blankIsEmptyWithoutAQuery(String blank) {
        assertThat(service.findByMobile(blank)).isEmpty();
        assertThat(service.findByMobile(null)).isEmpty();
        verify(userRepository, never()).findByMobileNumber(any());
    }

    // ----- grant -----

    @Test
    void grant_addsTheRoleAndTheNextRefreshedTokenCarriesIt() {
        UserAccount account = stubAccount(Role.CUSTOMER);
        String refresh = signIn(account);

        UserAccount result = service.grant(account.getId(), "TENANT_ADMIN");

        assertThat(result.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.TENANT_ADMIN);
        verify(userRepository).saveAndFlush(account);
        // The existing session survives a grant and picks the role up at its next refresh.
        RefreshService.RefreshResult refreshed = refreshService.refresh(refresh);
        assertThat(rolesIn(refreshed.tokens().accessToken())).containsExactly("CUSTOMER", "TENANT_ADMIN");
    }

    @Test
    void grant_isIdempotent() {
        UserAccount account = stubAccount(Role.CUSTOMER);
        account.addRole(Role.TENANT_ADMIN);

        UserAccount result = service.grant(account.getId(), "TENANT_ADMIN");

        assertThat(result.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.TENANT_ADMIN);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void grant_racingAnotherGrantOfTheSameRole_answersAsARepeat() {
        UserAccount stale = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        UserAccount afterTheOtherGrant = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        afterTheOtherGrant.addRole(Role.TENANT_ADMIN);
        when(userRepository.findById(stale.getId()))
                .thenReturn(Optional.of(stale), Optional.of(afterTheOtherGrant));
        when(userRepository.saveAndFlush(stale)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(service.grant(stale.getId(), "TENANT_ADMIN").getRoles()).contains(Role.TENANT_ADMIN);
    }

    @Test
    void grant_unknownAccount_isUserNotFound() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grant(unknown, "TENANT_ADMIN"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ----- revoke -----

    @Test
    void revoke_removesTheRoleAndEndsEverySession() {
        UserAccount account = stubAccount(Role.CUSTOMER);
        account.addRole(Role.TENANT_ADMIN);
        String phone = signIn(account);
        String laptop = signIn(account);

        UserAccount result = service.revoke(account.getId(), "TENANT_ADMIN");

        assertThat(result.getRoles()).containsExactly(Role.CUSTOMER);
        verify(userRepository).save(account);
        assertThatThrownBy(() -> refreshService.refresh(phone)).isInstanceOf(TokenException.class);
        assertThatThrownBy(() -> refreshService.refresh(laptop)).isInstanceOf(TokenException.class);
        // Signing in again yields tokens without the role, with no re-registration (MT-2.5).
        String again = signIn(result);
        assertThat(rolesIn(refreshService.refresh(again).tokens().accessToken())).containsExactly("CUSTOMER");
    }

    @Test
    void revoke_isIdempotentAndARepeatStillEndsSessions() {
        // A repeat after a partial failure (role removed, revocation failed) must finish the job.
        UserAccount account = stubAccount(Role.CUSTOMER);
        String refresh = signIn(account);

        UserAccount result = service.revoke(account.getId(), "TENANT_ADMIN");

        assertThat(result.getRoles()).containsExactly(Role.CUSTOMER);
        verify(userRepository, never()).save(any());
        assertThatThrownBy(() -> refreshService.refresh(refresh)).isInstanceOf(TokenException.class);
    }

    @Test
    void revoke_unknownAccount_isUserNotFound() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(unknown, "TENANT_ADMIN"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ----- only TENANT_ADMIN is manageable -----

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "TENANT_ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherRole_isRefusedOnGrantAndRevoke(Role role) {
        UserAccount account = stubAccount(Role.CUSTOMER);
        account.addRole(role);

        assertRefused(() -> service.grant(account.getId(), role.name()));
        assertRefused(() -> service.revoke(account.getId(), role.name()));
        assertThat(account.getRoles()).contains(role);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant_admin", " TENANT_ADMIN", "NOT_A_ROLE", ""})
    void unknownOrMisspelledRoleNames_areRefusedAlike(String roleName) {
        assertRefused(() -> service.grant(UUID.randomUUID(), roleName));
        assertRefused(() -> service.revoke(UUID.randomUUID(), roleName));
        verify(userRepository, never()).findById(any());
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AdminUserException.class)
                .satisfies(ex -> {
                    AdminUserException aue = (AdminUserException) ex;
                    assertThat(aue.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(aue.getErrorCode()).isEqualTo("ROLE_NOT_MANAGEABLE");
                });
    }
}
