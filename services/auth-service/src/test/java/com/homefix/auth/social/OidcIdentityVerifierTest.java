package com.homefix.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.auth.config.SocialLoginProperties.ProviderConfig;

import io.jsonwebtoken.Jwts;

/**
 * Unit tests for the OIDC identity-token verification base (Requirement 1.5, 23.10).
 *
 * <p>Signs real JWTs with a generated RSA key and validates them through a
 * {@link GoogleIdentityVerifier} whose {@link OidcKeyLocator} returns the matching public key,
 * exercising the signature/issuer/audience/expiry checks without contacting Google.
 */
class OidcIdentityVerifierTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String AUDIENCE = "homefix-client-id";
    private static final String KID = "test-key-1";

    private KeyPair keyPair;
    private OidcIdentityVerifier verifier;

    @BeforeEach
    void setUp() {
        keyPair = Jwts.SIG.RS256.keyPair().build();

        ProviderConfig config = new ProviderConfig();
        config.setIssuer(ISSUER);
        config.setAudience(AUDIENCE);

        OidcKeyLocator locator = (provider, keyId) -> {
            if (!KID.equals(keyId)) {
                throw SocialIdentityException.invalidToken();
            }
            return keyPair.getPublic();
        };
        // Reuse the Google adapter as a concrete OidcIdentityVerifier.
        verifier = new OidcIdentityVerifier(SocialProvider.GOOGLE, config, locator) {
        };
    }

    private String signToken(String subject, String issuer, String audience, Instant expiry, String email) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(subject)
                .claim("email", email)
                .issuedAt(Date.from(Instant.now().minusSeconds(10)))
                .expiration(Date.from(expiry))
                .signWith((PrivateKey) keyPair.getPrivate())
                .compact();
    }

    @Test
    void validToken_returnsVerifiedIdentity() {
        String token = signToken("google-sub-1", ISSUER, AUDIENCE,
                Instant.now().plusSeconds(300), "u@example.com");

        VerifiedSocialIdentity identity = verifier.verify(token);

        assertThat(identity.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(identity.providerSubject()).isEqualTo("google-sub-1");
        assertThat(identity.email()).isEqualTo("u@example.com");
    }

    @Test
    void expiredToken_throwsExpired() {
        String token = signToken("google-sub-1", ISSUER, AUDIENCE,
                Instant.now().minusSeconds(60), null);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_EXPIRED"));
    }

    @Test
    void wrongAudience_throwsInvalid() {
        String token = signToken("google-sub-1", ISSUER, "some-other-client",
                Instant.now().plusSeconds(300), null);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_INVALID"));
    }

    @Test
    void wrongIssuer_throwsInvalid() {
        String token = signToken("google-sub-1", "https://evil.example.com", AUDIENCE,
                Instant.now().plusSeconds(300), null);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(SocialIdentityException.class);
    }

    @Test
    void tamperedSignature_throwsInvalid() {
        String token = signToken("google-sub-1", ISSUER, AUDIENCE,
                Instant.now().plusSeconds(300), null);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(SocialIdentityException.class);
    }

    @Test
    void blankToken_throwsInvalid() {
        assertThatThrownBy(() -> verifier.verify("   "))
                .isInstanceOf(SocialIdentityException.class)
                .satisfies(ex -> assertThat(((SocialIdentityException) ex).getErrorCode())
                        .isEqualTo("SOCIAL_IDENTITY_TOKEN_INVALID"));
    }

    @Test
    void unknownKid_throwsInvalid() {
        String token = Jwts.builder()
                .header().keyId("unknown-kid").and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject("s")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith((PrivateKey) keyPair.getPrivate())
                .compact();

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(SocialIdentityException.class);
    }
}
