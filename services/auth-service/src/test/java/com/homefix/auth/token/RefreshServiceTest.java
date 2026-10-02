package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.shared.security.SecurityProperties;

import io.jsonwebtoken.Claims;

/**
 * Unit tests for refresh-token rotation, replay detection with family invalidation, logout
 * revocation, and multi-role JWT claims (Requirement 1.9, 1.10, 1.12, 1.14, 1.15, Property 26).
 *
 * <p>Uses a real {@link TokenService} over an in-memory token store, with the account
 * repository mocked — no Redis, DB, or Spring context.
 */
@ExtendWith(MockitoExtension.class)
class RefreshServiceTest {

    private static final String SECRET = "unit-test-signing-secret-that-is-32b+";

    private InMemoryRefreshTokenStore tokenStore;
    private TokenService tokenService;

    @Mock
    private UserAccountRepository userRepository;

    private RefreshService refreshService;

    @BeforeEach
    void setUp() {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(SECRET);
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));

        tokenStore = new InMemoryRefreshTokenStore();
        tokenService = new TokenService(security, tokenProps, tokenStore);
        refreshService = new RefreshService(tokenService, userRepository);
    }

    private UUID stubAccount(Role... roles) {
        UserAccount account = accountWithRoles(roles);
        lenient().when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account.getId();
    }

    private UserAccount accountWithRoles(Role... roles) {
        UserAccount account = UserAccount.createVerified("+911111111111", roles[0]);
        for (int i = 1; i < roles.length; i++) {
            account.addRole(roles[i]);
        }
        return account;
    }

    // ----- Rotation (Requirement 1.9) -----

    @Test
    void validRefreshToken_issuesNewAccessTokenAndRotatesRefreshToken() {
        UUID userId = stubAccount(Role.CUSTOMER);
        // Seed an initial family/token as issuance would.
        String initialRefresh = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        RefreshService.RefreshResult result = refreshService.refresh(initialRefresh);

        assertThat(result.tokens().accessToken()).isNotBlank();
        assertThat(result.tokens().refreshToken())
                .isNotBlank()
                .isNotEqualTo(initialRefresh);

        // New access token carries the account's roles and subject.
        Claims claims = tokenService.parseAndVerify(result.tokens().accessToken());
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get(TokenService.ROLES_CLAIM, List.class)).containsExactly("CUSTOMER");
    }

    @Test
    void rotatedRefreshToken_isItselfUsableOnce() {
        UUID userId = stubAccount(Role.CUSTOMER);
        String first = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        String second = refreshService.refresh(first).tokens().refreshToken();
        // The rotated (second) token works exactly once.
        String third = refreshService.refresh(second).tokens().refreshToken();

        assertThat(third).isNotBlank().isNotEqualTo(second);
    }

    // ----- Replay detection + family invalidation (Requirement 1.10, Property 26) -----

    @Test
    void replayingUsedRefreshToken_invalidatesEntireFamily() {
        UUID userId = stubAccount(Role.CUSTOMER);
        String familyId = UUID.randomUUID().toString();
        String first = tokenService.issueRefreshToken(userId.toString(), familyId);

        // Legitimate rotation: first -> second.
        String second = refreshService.refresh(first).tokens().refreshToken();

        // Attacker replays the already-used first token: family must be invalidated.
        assertThatThrownBy(() -> refreshService.refresh(first))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> {
                    TokenException te = (TokenException) ex;
                    assertThat(te.getErrorCode()).isEqualTo("REFRESH_TOKEN_REPLAY");
                    assertThat(te.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        // The legitimate successor is now also dead — no family member can rotate.
        assertThatThrownBy(() -> refreshService.refresh(second))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    // ----- Invalid / revoked token (Requirement 1.15) -----

    @Test
    void unknownRefreshToken_returns401Invalid() {
        assertThatThrownBy(() -> refreshService.refresh("does-not-exist"))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void blankRefreshToken_returns401Invalid() {
        assertThatThrownBy(() -> refreshService.refresh("  "))
                .isInstanceOf(TokenException.class);
    }

    // ----- Logout revocation (Requirement 1.12) -----

    @Test
    void logout_revokesRefreshTokenSoItCannotRotate() {
        UUID userId = stubAccount(Role.CUSTOMER);
        String refresh = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        refreshService.logout(refresh);

        assertThatThrownBy(() -> refreshService.refresh(refresh))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void logout_withARotatedTokenAlsoKillsTheThiefsSuccessor() {
        UUID userId = stubAccount(Role.CUSTOMER);
        String victims = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());
        // The thief rotates the stolen token first; the victim's copy is now a used member.
        String thiefs = refreshService.refresh(victims).tokens().refreshToken();

        refreshService.logout(victims);

        assertThatThrownBy(() -> refreshService.refresh(thiefs))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
        assertThat(tokenStore.find(thiefs)).isEmpty();
        assertThat(tokenStore.find(victims)).isEmpty();
    }

    @Test
    void logout_endsOnlyThatLoginsFamily() {
        UUID userId = stubAccount(Role.CUSTOMER);
        String phone = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());
        String laptop = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        refreshService.logout(phone);

        assertThat(refreshService.refresh(laptop).tokens().refreshToken()).isNotBlank();
    }

    @Test
    void logout_isIdempotentForUnknownToken() {
        // Should not throw.
        refreshService.logout("never-issued");
        refreshService.logout(null);
    }

    // ----- Multi-role JWT claims (Requirement 1.14) -----

    @Test
    void rotation_embedsAllHeldRolesInAccessToken() {
        UserAccount dual = accountWithRoles(Role.CUSTOMER, Role.SERVICE_PROVIDER);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(dual));

        String refresh = tokenService.issueRefreshToken(dual.getId().toString(), UUID.randomUUID().toString());
        RefreshService.RefreshResult result = refreshService.refresh(refresh);

        assertThat(result.roles()).containsExactly("CUSTOMER", "SERVICE_PROVIDER");
        Claims claims = tokenService.parseAndVerify(result.tokens().accessToken());
        assertThat(claims.get(TokenService.ROLES_CLAIM, List.class))
                .containsExactly("CUSTOMER", "SERVICE_PROVIDER");
    }

    @Test
    void issueTokens_startsUsableFamily_sanityCheck() {
        // Guards TokenService.issueTokens wiring used by registration & social login.
        UUID userId = stubAccount(Role.CUSTOMER);
        TokenPair pair = tokenService.issueTokens(userId.toString(), List.of("CUSTOMER"));
        assertThat(tokenStore.find(pair.refreshToken())).isPresent();
        assertThat(EnumSet.of(Role.CUSTOMER)).isNotEmpty();
    }

    // ----- Disabled accounts (Requirement 19.2) -----

    @Test
    void suspendedAccount_cannotRefreshAndTheFamilyStaysDeadAfterReactivation() {
        UserAccount account = accountWithRoles(Role.CUSTOMER);
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        String refresh = tokenService.issueRefreshToken(account.getId().toString(), UUID.randomUUID().toString());
        account.changeStatus(AccountStatus.SUSPENDED);

        assertThatThrownBy(() -> refreshService.refresh(refresh))
                .isInstanceOf(AccountDisabledException.class)
                .satisfies(ex -> {
                    AccountDisabledException ade = (AccountDisabledException) ex;
                    assertThat(ade.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ade.getErrorCode()).isEqualTo("ACCOUNT_DISABLED");
                });

        // Reactivating the account does not bring that session back.
        account.changeStatus(AccountStatus.ACTIVE);
        assertThatThrownBy(() -> refreshService.refresh(refresh))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void deactivatedAccount_cannotRefresh() {
        UserAccount account = accountWithRoles(Role.ADMIN);
        account.changeStatus(AccountStatus.DEACTIVATED);
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        String refresh = tokenService.issueRefreshToken(account.getId().toString(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> refreshService.refresh(refresh))
                .isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void revokeAllRefreshTokens_endsEveryLoginOfThatAccountOnly() {
        UUID userId = stubAccount(Role.CUSTOMER);
        UUID otherUserId = stubAccount(Role.CUSTOMER);
        String phoneLogin = tokenService.issueTokens(userId.toString(), List.of("CUSTOMER")).refreshToken();
        String laptopLogin = tokenService.issueTokens(userId.toString(), List.of("CUSTOMER")).refreshToken();
        String rotated = refreshService.refresh(laptopLogin).tokens().refreshToken();
        String otherLogin = tokenService.issueTokens(otherUserId.toString(), List.of("CUSTOMER")).refreshToken();

        tokenService.revokeAllRefreshTokens(userId.toString());

        assertThatThrownBy(() -> refreshService.refresh(phoneLogin)).isInstanceOf(TokenException.class);
        assertThatThrownBy(() -> refreshService.refresh(rotated)).isInstanceOf(TokenException.class);
        assertThat(refreshService.refresh(otherLogin).tokens().refreshToken()).isNotBlank();
    }

    @Test
    void nonUuidSubject_isAnInvalidTokenNotAServerError() {
        String refresh = tokenService.issueRefreshToken("not-a-uuid", UUID.randomUUID().toString());

        assertThatThrownBy(() -> refreshService.refresh(refresh))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }
}
