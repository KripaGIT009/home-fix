package com.homefix.auth.social;

/**
 * Port for validating a social provider's identity token and extracting the verified identity
 * (Requirement 1.5).
 *
 * <p>Abstracting verification behind this port lets each provider (Google, Apple) supply its
 * own adapter — validating the token's signature against the provider's published keys,
 * checking issuer/audience/expiry — while the {@code SocialLoginService} stays provider-agnostic.
 * It also lets tests substitute a deterministic fake so the login flow can be unit-tested
 * without calling out to Google/Apple.
 */
public interface SocialIdentityVerifier {

    /** @return the provider this verifier handles. */
    SocialProvider provider();

    /**
     * Validates the provider identity token and returns the verified identity.
     *
     * @param identityToken the raw identity/ID token issued by the provider
     * @return the verified identity (provider subject + optional email)
     * @throws SocialIdentityException if the token is invalid, expired, or otherwise untrusted
     */
    VerifiedSocialIdentity verify(String identityToken);
}
