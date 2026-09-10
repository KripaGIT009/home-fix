package com.homefix.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.domain.SocialIdentityLink;
import com.homefix.auth.domain.SocialIdentityLinkRepository;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.auth.token.TokenService;
import com.homefix.shared.security.SecurityProperties;

import io.jsonwebtoken.Claims;

/**
 * Unit tests for social login (Requirement 1.5, 1.14).
 *
 * <p>Substitutes a deterministic {@link SocialIdentityVerifier} fake for the Google/Apple
 * adapters (the verifier port exists precisely so this can be mocked), and uses lightweight
 * in-memory repositories. The real {@link TokenService} issues tokens so claims can be asserted.
 */
class SocialLoginServiceTest {

    private static final String GOOGLE_SUBJECT = "google-sub-12345";
    private static final String VALID_TOKEN = "valid-google-token";

    /** In-memory user store shared by the mock repository stubs. */
    private final Map<UUID, UserAccount> users = new HashMap<>();
    /** In-memory link store keyed on provider+subject. */
    private final Map<String, SocialIdentityLink> links = new HashMap<>();

    private SocialIdentityLinkRepository linkRepository;
    private UserAccountRepository userRepository;
    private TokenService tokenService;
    private SocialLoginService service;

    @BeforeEach
    void setUp() {
        SocialIdentityVerifierResolver resolver =
                new SocialIdentityVerifierResolver(List.of(new FakeGoogleVerifier()));

        userRepository = mock(UserAccountRepository.class);
        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0, UUID.class))));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> {
            UserAccount a = inv.getArgument(0);
            users.put(a.getId(), a);
            return a;
        });

        linkRepository = mock(SocialIdentityLinkRepository.class);
        when(linkRepository.findByProviderAndProviderSubject(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(links.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(linkRepository.save(any(SocialIdentityLink.class))).thenAnswer(inv -> {
            SocialIdentityLink l = inv.getArgument(0);
            links.put(l.getProvider() + ":" + l.getProviderSubject(), l);
            return l;
        });

        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret("unit-test-signing-secret-that-is-32b+");
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));
        tokenService = new TokenService(security, tokenProps, new InMemoryRefreshTokenStore());

        service = new SocialLoginService(resolver, linkRepository, userRepository, tokenService);
    }

    // ----- Valid token, first login: creates account (Requirement 1.5) -----

    @Test
    void validToken_firstLogin_createsAccountWithCustomerRole() {
        SocialLoginService.SocialLoginResult result = service.login(SocialProvider.GOOGLE, VALID_TOKEN);

        assertThat(result.roles()).containsExactly("CUSTOMER");
        assertThat(result.tokens().accessToken()).isNotBlank();
        assertThat(result.tokens().refreshToken()).isNotBlank();
        assertThat(users).hasSize(1);
        assertThat(links).containsKey(SocialProvider.GOOGLE + ":" + GOOGLE_SUBJECT);

        Claims claims = tokenService.parseAndVerify(result.tokens().accessToken());
        assertThat(claims.get(TokenService.ROLES_CLAIM, List.class)).containsExactly("CUSTOMER");
    }

    // ----- Valid token, repeat login: retrieves the same account (Requirement 1.5) -----

    @Test
    void validToken_subsequentLogin_retrievesSameAccount() {
        String firstUserId = service.login(SocialProvider.GOOGLE, VALID_TOKEN).userId();
        String secondUserId = service.login(SocialProvider.GOOGLE, VALID_TOKEN).userId();

        assertThat(secondUserId).isEqualTo(firstUserId);
        // No duplicate account or link created on the second login.
        assertThat(users).hasSize(1);
        assertThat(links).hasSize(1);
    }

    // ----- Invalid / expired token: 401 with error code (Requirement 1.5) -----

    @Test
    void invalidToken_returns401WithErrorCode() {
        assertThatThrownBy(() -> service.login(SocialProvider.GOOGLE, "garbage"))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> {
                    SocialIdentityException se = (SocialIdentityException) ex;
                    assertThat(se.getStatus().value()).isEqualTo(401);
                    assertThat(se.getErrorCode()).isEqualTo("SOCIAL_IDENTITY_TOKEN_INVALID");
                });
        assertThat(users).isEmpty();
    }

    @Test
    void expiredToken_returns401Expired() {
        assertThatThrownBy(() -> service.login(SocialProvider.GOOGLE, "expired"))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_EXPIRED"));
    }

    @Test
    void unsupportedProvider_returns401() {
        // No Apple verifier registered in this resolver.
        assertThatThrownBy(() -> service.login(SocialProvider.APPLE, VALID_TOKEN))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_PROVIDER_UNSUPPORTED"));
    }

    // ===== Fakes =====

    private static final class FakeGoogleVerifier implements SocialIdentityVerifier {
        @Override
        public SocialProvider provider() {
            return SocialProvider.GOOGLE;
        }

        @Override
        public VerifiedSocialIdentity verify(String identityToken) {
            return switch (identityToken) {
                case VALID_TOKEN -> new VerifiedSocialIdentity(
                        SocialProvider.GOOGLE, GOOGLE_SUBJECT, "user@example.com");
                case "expired" -> throw SocialIdentityException.expiredToken();
                default -> throw SocialIdentityException.invalidToken();
            };
        }
    }

}
