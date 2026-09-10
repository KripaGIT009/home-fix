package com.homefix.auth.social;

import java.security.Key;

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
        this.provider = provider;
        this.config = config;
        this.keyLocator = keyLocator;
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
