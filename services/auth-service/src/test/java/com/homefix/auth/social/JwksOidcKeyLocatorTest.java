package com.homefix.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.homefix.auth.config.SocialLoginProperties;

/**
 * Unit tests for {@link JwksOidcKeyLocator} covering the paths that do not require a reachable
 * JWKS endpoint (Requirement 23.10): blank {@code kid} rejection, unconfigured provider, and
 * network/refresh failure surfacing as a 401-mapped {@link SocialIdentityException}.
 */
class JwksOidcKeyLocatorTest {

    @Test
    void blankKeyId_throwsInvalidToken() {
        JwksOidcKeyLocator locator = new JwksOidcKeyLocator(new SocialLoginProperties());

        assertThatThrownBy(() -> locator.locate(SocialProvider.GOOGLE, "  "))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_INVALID"));
    }

    @Test
    void nullKeyId_throwsInvalidToken() {
        JwksOidcKeyLocator locator = new JwksOidcKeyLocator(new SocialLoginProperties());

        assertThatThrownBy(() -> locator.locate(SocialProvider.GOOGLE, null))
                .isInstanceOf(SocialIdentityException.class);
    }

    @Test
    void unconfiguredJwksUri_throwsUnsupportedProvider() {
        // Default SocialLoginProperties has no jwks-uri set for either provider.
        JwksOidcKeyLocator locator = new JwksOidcKeyLocator(new SocialLoginProperties());

        assertThatThrownBy(() -> locator.locate(SocialProvider.GOOGLE, "some-kid"))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_PROVIDER_UNSUPPORTED"));
    }

    @Test
    void unreachableJwksEndpoint_throwsInvalidToken() {
        SocialLoginProperties properties = new SocialLoginProperties();
        // Point at a reserved TEST-NET address that will not accept connections, so the
        // refresh fails and the locator maps the failure to a 401-invalid token.
        properties.getGoogle().setJwksUri("http://192.0.2.1:1/jwks.json");

        JwksOidcKeyLocator locator = new JwksOidcKeyLocator(properties);

        assertThatThrownBy(() -> locator.locate(SocialProvider.GOOGLE, "some-kid"))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_INVALID"));
    }
}
