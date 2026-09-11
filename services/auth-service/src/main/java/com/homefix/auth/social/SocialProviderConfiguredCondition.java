package com.homefix.auth.social;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when a social login provider is <em>fully</em> configured, i.e. its {@code issuer},
 * {@code audience} and {@code jwks-uri} all resolve to non-blank values.
 *
 * <p>Social login is optional: a deployment that has no Google/Apple credentials must still start,
 * simply without offering those providers. Gating the verifier beans on this condition keeps that
 * property while letting {@link OidcIdentityVerifier} reject a half-configured provider outright —
 * in particular a blank {@code audience}, which would disable the {@code aud} check and accept any
 * identity token the provider ever issued, for any application.
 *
 * <p>An unconfigured provider therefore has no {@link SocialIdentityVerifier} bean, and
 * {@link SocialIdentityVerifierResolver} answers a login attempt for it with the existing
 * "unsupported provider" 401 rather than a 500.
 */
public abstract class SocialProviderConfiguredCondition implements Condition {

    private final String propertyPrefix;

    protected SocialProviderConfiguredCondition(String providerKey) {
        this.propertyPrefix = "homefix.auth.social." + providerKey + ".";
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return isConfigured(environment, "issuer")
                && isConfigured(environment, "audience")
                && isConfigured(environment, "jwks-uri");
    }

    private boolean isConfigured(Environment environment, String name) {
        String value;
        try {
            value = environment.getProperty(propertyPrefix + name);
        } catch (IllegalArgumentException ex) {
            // The value is a placeholder such as ${GOOGLE_CLIENT_ID} that nothing supplied:
            // the provider is not configured, which is allowed — it is just not offered.
            return false;
        }
        return value != null && !value.isBlank();
    }

    /** Google Sign-In wiring condition. */
    public static final class Google extends SocialProviderConfiguredCondition {
        public Google() {
            super("google");
        }
    }

    /** Sign in with Apple wiring condition. */
    public static final class Apple extends SocialProviderConfiguredCondition {
        public Apple() {
            super("apple");
        }
    }
}
