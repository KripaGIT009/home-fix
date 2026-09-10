package com.homefix.auth.social;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.homefix.auth.config.SocialLoginProperties;

/**
 * Wiring tests for the concrete {@link GoogleIdentityVerifier} and {@link AppleIdentityVerifier}
 * adapters (Requirement 1.5, 23.10).
 *
 * <p>Confirms each adapter binds to its provider using the matching per-provider config so the
 * {@link SocialIdentityVerifierResolver} can dispatch on {@link SocialProvider}.
 */
class ProviderVerifierWiringTest {

    private final OidcKeyLocator noopLocator = (provider, keyId) -> {
        throw SocialIdentityException.invalidToken();
    };

    @Test
    void googleVerifier_reportsGoogleProvider() {
        SocialLoginProperties properties = new SocialLoginProperties();
        properties.getGoogle().setIssuer("https://accounts.google.com");
        properties.getGoogle().setAudience("google-client");

        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier(properties, noopLocator);

        assertThat(verifier.provider()).isEqualTo(SocialProvider.GOOGLE);
    }

    @Test
    void appleVerifier_reportsAppleProvider() {
        SocialLoginProperties properties = new SocialLoginProperties();
        properties.getApple().setIssuer("https://appleid.apple.com");
        properties.getApple().setAudience("apple-services");

        AppleIdentityVerifier verifier = new AppleIdentityVerifier(properties, noopLocator);

        assertThat(verifier.provider()).isEqualTo(SocialProvider.APPLE);
    }
}
