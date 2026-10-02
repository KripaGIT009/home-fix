package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.shared.security.SecurityProperties;

/**
 * Unit tests for {@link IntrospectionService}: the API Gateway's view of a token is "active" only
 * while the token verifies and its account may still authenticate (Requirement 19.2), so
 * suspending an account ends its existing sessions without waiting for access-token expiry.
 */
@ExtendWith(MockitoExtension.class)
class IntrospectionServiceTest {

    private static final String SECRET = "unit-test-signing-secret-that-is-32b+";

    @Mock
    private UserAccountRepository userRepository;

    private TokenService tokenService;
    private IntrospectionService introspection;

    @BeforeEach
    void setUp() {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(SECRET);
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));
        tokenService = new TokenService(security, tokenProps, new InMemoryRefreshTokenStore());
        introspection = new IntrospectionService(tokenService, userRepository);
    }

    @Test
    void activeAccount_tokenIsActiveWithItsClaims() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findStatusById(userId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        String token = tokenService.issueAccessToken(userId.toString(), List.of("CUSTOMER"));

        Map<String, Object> result = introspection.introspect(token);

        assertThat(result).containsEntry("active", true)
                .containsEntry("sub", userId.toString())
                .containsEntry("roles", List.of("CUSTOMER"));
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void disabledAccount_unexpiredTokenIsReportedInactive(AccountStatus status) {
        UUID userId = UUID.randomUUID();
        when(userRepository.findStatusById(userId)).thenReturn(Optional.of(status));
        String token = tokenService.issueAccessToken(userId.toString(), List.of("ADMIN"));

        assertThat(introspection.introspect(token)).isEqualTo(Map.of("active", false));
    }

    @Test
    void unknownAccount_tokenIsReportedInactive() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findStatusById(userId)).thenReturn(Optional.empty());
        String token = tokenService.issueAccessToken(userId.toString(), List.of("CUSTOMER"));

        assertThat(introspection.introspect(token)).isEqualTo(Map.of("active", false));
    }

    @Test
    void invalidToken_isInactiveWithoutADatabaseRead() {
        assertThat(introspection.introspect("not-a-jwt")).isEqualTo(Map.of("active", false));
        assertThat(introspection.introspect(null)).isEqualTo(Map.of("active", false));

        verify(userRepository, never()).findStatusById(any());
    }

    @Test
    void nonUuidSubject_isInactiveWithoutADatabaseRead() {
        String token = tokenService.issueAccessToken("service-account", List.of("ADMIN"));

        assertThat(introspection.introspect(token)).isEqualTo(Map.of("active", false));
        verify(userRepository, never()).findStatusById(any());
    }
}
