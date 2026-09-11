package com.homefix.auth.social;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link SocialIdentityVerifier} for a given {@link SocialProvider}.
 *
 * <p>All verifier beans on the classpath are collected at startup and indexed by provider, so
 * adding support for a new provider requires only a new {@link SocialIdentityVerifier} bean —
 * no changes here or in {@code SocialLoginService}.
 *
 * <p>A provider that is not fully configured registers no verifier bean (see
 * {@link SocialProviderConfiguredCondition}), so a login attempt for it fails with the
 * "unsupported provider" 401 below instead of preventing the service from starting.
 */
@Component
public class SocialIdentityVerifierResolver {

    private final Map<SocialProvider, SocialIdentityVerifier> verifiers = new EnumMap<>(SocialProvider.class);

    /**
     * Spring-facing constructor. Takes an {@link ObjectProvider} rather than a {@code List} so a
     * deployment with <em>no</em> social provider configured still starts: social login is
     * optional, and an unconfigured provider registers no verifier bean at all.
     */
    @Autowired
    public SocialIdentityVerifierResolver(ObjectProvider<SocialIdentityVerifier> beans) {
        this(beans.orderedStream().toList());
    }

    public SocialIdentityVerifierResolver(List<SocialIdentityVerifier> beans) {
        for (SocialIdentityVerifier verifier : beans) {
            verifiers.put(verifier.provider(), verifier);
        }
    }

    /**
     * @return the verifier for the provider
     * @throws SocialIdentityException with a 401 if no verifier is registered for the provider
     */
    public SocialIdentityVerifier resolve(SocialProvider provider) {
        SocialIdentityVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            throw SocialIdentityException.unsupportedProvider(String.valueOf(provider));
        }
        return verifier;
    }
}
