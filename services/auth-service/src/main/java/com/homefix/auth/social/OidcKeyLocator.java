package com.homefix.auth.social;

import java.security.Key;

/**
 * Resolves the verification key for a provider identity token from its JWKS, keyed by the
 * token's {@code kid} header.
 *
 * <p>Extracted as a port so the JWKS HTTP fetch (and its caching) is pluggable and can be
 * replaced with a deterministic key in tests. Real adapters back this with the provider's
 * published JWKS endpoint (Google/Apple) per OIDC (Requirement 23.10).
 */
public interface OidcKeyLocator {

    /**
     * @param provider the social provider whose JWKS to consult
     * @param keyId    the {@code kid} header from the token, may be {@code null}
     * @return the public key to verify the token signature with
     * @throws SocialIdentityException if no matching key can be located
     */
    Key locate(SocialProvider provider, String keyId);
}
