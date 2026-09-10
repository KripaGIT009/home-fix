package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.shared.security.SecurityProperties;

/**
 * Unit tests for JWT issuance and introspection (Requirement 1.8; introspect endpoint).
 */
class TokenServiceTest {

    private static final String SECRET = "unit-test-signing-secret-that-is-32b+";

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(SECRET);

        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setIssuer("homefix-auth");
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));

        tokenService = new TokenService(security, tokenProps, new InMemoryRefreshTokenStore());
    }

    @Test
    void issuedAccessToken_hasSubjectRolesAndFifteenMinuteExpiry() {
        String token = tokenService.issueAccessToken("user-1", List.of("CUSTOMER"));
        var claims = tokenService.parseAndVerify(token);

        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("roles", List.class)).containsExactly("CUSTOMER");
        assertThat(claims.getIssuer()).isEqualTo("homefix-auth");

        long lifetimeSeconds = Duration.between(
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()).getSeconds();
        assertThat(lifetimeSeconds).isEqualTo(900L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void introspect_validToken_returnsActiveClaims() {
        String token = tokenService.issueAccessToken("user-42", List.of("CUSTOMER", "SERVICE_PROVIDER"));

        Map<String, Object> result = tokenService.introspect(token);

        assertThat(result.get("active")).isEqualTo(true);
        assertThat(result.get("sub")).isEqualTo("user-42");
        assertThat(result.get("iss")).isEqualTo("homefix-auth");
        assertThat((List<String>) result.get("roles")).containsExactly("CUSTOMER", "SERVICE_PROVIDER");
    }

    @Test
    void introspect_invalidSignature_returnsInactive() {
        // Token signed with a different secret.
        SecurityProperties otherSecurity = new SecurityProperties();
        otherSecurity.setJwtSecret("a-totally-different-secret-32-bytes!!");
        TokenService foreignIssuer = new TokenService(
                otherSecurity, new AuthTokenProperties(), new InMemoryRefreshTokenStore());

        String foreignToken = foreignIssuer.issueAccessToken("user-1", List.of("CUSTOMER"));

        Map<String, Object> result = tokenService.introspect(foreignToken);
        assertThat(result.get("active")).isEqualTo(false);
    }

    @Test
    void introspect_malformedToken_returnsInactive() {
        assertThat(tokenService.introspect("not-a-jwt").get("active")).isEqualTo(false);
    }

    @Test
    void introspect_nullOrBlankToken_returnsInactive() {
        assertThat(tokenService.introspect(null).get("active")).isEqualTo(false);
        assertThat(tokenService.introspect("   ").get("active")).isEqualTo(false);
    }
}
