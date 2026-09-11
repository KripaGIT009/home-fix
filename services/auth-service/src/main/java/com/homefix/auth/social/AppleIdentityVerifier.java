package com.homefix.auth.social;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.homefix.auth.config.SocialLoginProperties;

/**
 * Sign in with Apple identity-token verifier (Requirement 1.5, 23.10).
 *
 * <p>Validates the Apple-issued OIDC ID token (signature against Apple's JWKS, {@code iss}
 * {@code https://appleid.apple.com}, {@code aud} = this platform's Services ID, and expiry) and
 * extracts the Apple {@code sub} and, when present, {@code email}.
 *
 * <p>Registered only when Sign in with Apple is fully configured (see
 * {@link SocialProviderConfiguredCondition}); otherwise the provider is simply not offered.
 */
@Component
@Conditional(SocialProviderConfiguredCondition.Apple.class)
public class AppleIdentityVerifier extends OidcIdentityVerifier {

    public AppleIdentityVerifier(SocialLoginProperties properties, OidcKeyLocator keyLocator) {
        super(SocialProvider.APPLE, properties.getApple(), keyLocator);
    }
}
