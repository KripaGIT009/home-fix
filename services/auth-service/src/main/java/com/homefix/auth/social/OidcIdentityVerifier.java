package com.homefix.auth.social;

import java.security.Key;
import java.util.Locale;

import com.homefix.auth.config.SocialLoginProperties.ProviderConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;

/**
 * OIDC-compliant identity-token verifier shared by the Google and Apple adapters
 * (Requirement 1.5, 23.10).
 *
 * <p>Validates the identity token per OpenID Connect: the signature is checked against the
 * provider's JWKS key (resolved via {@link OidcKeyLocator} by the token's {@code kid}), and the
 * {@code iss}, {@code aud}, and {@code exp} claims are enforced. On success the {@code sub}
 * (stable provider subject) and optional {@code email} are extracted. Any failure surfaces as a
 * {@link SocialIdentityException} which the REST layer maps to a 401 with an error code
 * identifying the reason.
 */
public abstract class OidcIdentityVerifier implements SocialIdentityVerifier {

    private final SocialProvider provider;
    private final ProviderConfig config;
    private final OidcKeyLocator keyLocator;

    protected OidcIdentityVerifier(SocialProvider provider, ProviderConfig config, OidcKeyLocator keyLocator) {
        if (provider == null) {
            throw new IllegalStateException("Social identity verifier requires a provider");
        }
        if (config == null) {
            throw new IllegalStateException(
                    "No OIDC configuration present for social provider " + provider
                            + "; configure " + propertyPrefix(provider) + ".* or leave the provider unconfigured");
        }
        // A blank audience would be silently dropped by the JWT parser, so the 'aud' claim would
        // go unchecked and ANY provider-issued identity token — for any application — would be
        // accepted. Refuse to build a verifier that cannot enforce the audience.
        requireConfigured(provider, "audience", config.getAudience());
        requireConfigured(provider, "issuer", config.getIssuer());
        if (keyLocator == null) {
            throw new IllegalStateException(
                    "No OIDC key locator available for social provider " + provider
                            + "; the identity-token signature could not be verified");
        }
        this.provider = provider;
        this.config = config;
        this.keyLocator = keyLocator;
    }

    private static void requireConfigured(SocialProvider provider, String name, String value) {
        if (value == null || value.isBlank() || isUnresolvedPlaceholder(value)) {
            throw new IllegalStateException(
                    propertyPrefix(provider) + "." + name + " must be configured with a non-blank value"
                            + " before social login for " + provider + " can be enabled");
        }
    }

    /** True for a value such as {@code ${GOOGLE_CLIENT_ID}} that no property source supplied. */
    private static boolean isUnresolvedPlaceholder(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }

    private static String propertyPrefix(SocialProvider provider) {
        return "homefix.auth.social." + provider.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public SocialProvider provider() {
        return provider;
    }

    @Override
    public VerifiedSocialIdentity verify(String identityToken) {
        if (identityToken == null || identityToken.isBlank()) {
            throw SocialIdentityException.invalidToken();
        }

        Claims claims;
        try {
            Jws<Claims> jws = Jwts.parser()
                    .keyLocator(header -> {
                        String kid = header instanceof io.jsonwebtoken.ProtectedHeader ph
                                ? ph.getKeyId() : null;
                        return keyLocator.locate(provider, kid);
                    })
                    .requireIssuer(config.getIssuer())
                    .requireAudience(config.getAudience())
                    .build()
                    .parseSignedClaims(identityToken);
            claims = jws.getPayload();
        } catch (ExpiredJwtException ex) {
            throw SocialIdentityException.expiredToken();
        } catch (SocialIdentityException ex) {
            throw ex;
        } catch (JwtException | IllegalArgumentException ex) {
            throw SocialIdentityException.invalidToken();
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw SocialIdentityException.invalidToken();
        }
        Object email = claims.get("email");
        return new VerifiedSocialIdentity(provider, subject, email == null ? null : email.toString());
    }
}
