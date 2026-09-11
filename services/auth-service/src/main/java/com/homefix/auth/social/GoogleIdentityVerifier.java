package com.homefix.auth.social;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.homefix.auth.config.SocialLoginProperties;

/**
 * Google Sign-In identity-token verifier (Requirement 1.5, 23.10).
 *
 * <p>Validates the Google-issued OIDC ID token (signature against Google's JWKS, {@code iss}
 * {@code https://accounts.google.com}, {@code aud} = this platform's OAuth client id, and
 * expiry) and extracts the Google {@code sub} and {@code email}.
 *
 * <p>Registered only when Google Sign-In is fully configured (see
 * {@link SocialProviderConfiguredCondition}); otherwise the provider is simply not offered.
 */
@Component
@Conditional(SocialProviderConfiguredCondition.Google.class)
public class GoogleIdentityVerifier extends OidcIdentityVerifier {

    public GoogleIdentityVerifier(SocialLoginProperties properties, OidcKeyLocator keyLocator) {
        super(SocialProvider.GOOGLE, properties.getGoogle(), keyLocator);
    }
}
