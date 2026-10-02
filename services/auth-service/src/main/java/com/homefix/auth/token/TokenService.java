package com.homefix.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.shared.security.SecurityProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and introspects JWT access tokens and issues, rotates, and revokes opaque refresh
 * tokens.
 *
 * <p>Access tokens are HMAC-SHA256 signed with the same secret consumed by the shared
 * {@code JwtValidationFilter} (Task 4), and carry the {@code sub} (user id) and {@code roles}
 * claims the filter expects, so tokens issued here validate downstream without extra
 * configuration.
 *
 * <p>Refresh tokens are opaque random values stored via the {@link RefreshTokenStore} with a
 * 30-day TTL (Requirement 1.8). Each token belongs to a token <em>family</em>: rotation issues
 * a successor in the same family (Requirement 1.9), and reuse of an already-rotated token
 * invalidates the whole family (Requirement 1.10, Property 26).
 */
@Service
public class TokenService {

    /** Claim key carrying the role list — must match {@code JwtValidationFilter.ROLES_CLAIM}. */
    public static final String ROLES_CLAIM = "roles";

    private final SecretKey signingKey;
    private final AuthTokenProperties tokenProperties;
    private final RefreshTokenStore refreshTokenStore;

    public TokenService(SecurityProperties securityProperties,
                        AuthTokenProperties tokenProperties,
                        RefreshTokenStore refreshTokenStore) {
        this.signingKey = Keys.hmacShaKeyFor(
                securityProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.tokenProperties = tokenProperties;
        this.refreshTokenStore = refreshTokenStore;
    }

    /**
     * Issues an access token + refresh token pair for the given subject and roles, starting a
     * brand-new token family (used on registration, verification, and social login).
     */
    public TokenPair issueTokens(String subject, List<String> roles) {
        String accessToken = issueAccessToken(subject, roles);
        String refreshToken = issueRefreshToken(subject, UUID.randomUUID().toString());
        return new TokenPair(accessToken, refreshToken, tokenProperties.getAccessTtl().getSeconds());
    }

    /**
     * Builds a signed access token with a 15-minute (configurable) validity.
     */
    public String issueAccessToken(String subject, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenProperties.getAccessTtl());
        return Jwts.builder()
                .issuer(tokenProperties.getIssuer())
                .subject(subject)
                .claim(ROLES_CLAIM, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generates an opaque refresh token in the supplied family and stores it with a 30-day TTL.
     * Returns the raw token to hand back to the client.
     */
    public String issueRefreshToken(String subject, String familyId) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        if (!refreshTokenStore.save(token, subject, familyId, tokenProperties.getRefreshTtl())) {
            // The family was revoked between consuming the presented token and storing its
            // successor: a concurrent replay of the same token was detected. The successor must
            // not be handed out, so this rotation fails like any other request on a dead family.
            throw TokenException.invalidRefreshToken();
        }
        return token;
    }

    /**
     * Validates a presented refresh token for rotation and consumes it, returning the record so
     * the caller can look up the account's current roles.
     *
     * <p>Validation and consumption are one atomic store operation
     * ({@link RefreshTokenStore#consume}), so of any number of concurrent refreshes presenting
     * the same token exactly one proceeds; the others observe it already used.
     *
     * <p>If the presented token is unknown/expired/revoked, throws a 401 {@link TokenException}
     * (Requirement 1.15). If the token was already rotated (replay, including a concurrent second
     * refresh of the same token), invalidates the entire token family and throws a 401
     * (Requirement 1.10, Property 26). Otherwise the token is now marked used and its record
     * returned.
     *
     * @return the (now consumed) token record, carrying the subject and family id
     */
    public RefreshTokenRecord consumeRefreshTokenForRotation(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw TokenException.invalidRefreshToken();
        }

        RefreshTokenRecord prior = refreshTokenStore
                .consume(presentedToken, tokenProperties.getRefreshTtl())
                .orElseThrow(TokenException::invalidRefreshToken);

        // Replay: the presented token was already rotated. Invalidate the whole family.
        if (prior.used()) {
            refreshTokenStore.revokeFamily(prior.familyId());
            throw TokenException.replayDetected();
        }

        return prior.markUsed();
    }

    /**
     * Issues a rotated access + refresh token pair for a consumed refresh-token record, keeping
     * the successor refresh token in the same family (Requirement 1.9).
     *
     * @throws TokenException 401 if the family was revoked after the token was consumed
     */
    public TokenPair issueRotatedTokens(RefreshTokenRecord consumed, List<String> roles) {
        String accessToken = issueAccessToken(consumed.subject(), roles);
        String newRefreshToken = issueRefreshToken(consumed.subject(), consumed.familyId());
        return new TokenPair(accessToken, newRefreshToken, tokenProperties.getAccessTtl().getSeconds());
    }

    /**
     * @return the record for a refresh token if present, for callers that need the subject.
     */
    public Optional<RefreshTokenRecord> findRefreshToken(String token) {
        return refreshTokenStore.find(token);
    }

    /**
     * Ends the session a refresh token belongs to, on logout within the request cycle
     * (Requirement 1.12). Idempotent: an unknown or already-revoked token is a no-op.
     *
     * <p>The whole token <em>family</em> is revoked, not just the presented token. A family is one
     * login; every rotation adds a member. Deleting only the presented token left the rest of the
     * family alive: if a stolen token had already been rotated by the thief, the victim's logout
     * (with their now-used token, which is still on record) removed that one key and the thief's
     * successor stayed valid for the remaining 30 days. Revoking the family also leaves the
     * revoked marker behind, so a rotation racing the logout cannot store a live successor.
     */
    public void revokeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        refreshTokenStore.find(token).ifPresentOrElse(
                record -> refreshTokenStore.revokeFamily(record.familyId()),
                () -> refreshTokenStore.revoke(token));
    }

    /**
     * Revokes one token family outright, e.g. when a refresh is refused because the account has
     * been disabled. Idempotent.
     */
    public void revokeTokenFamily(String familyId) {
        refreshTokenStore.revokeFamily(familyId);
    }

    /**
     * Ends every session of an account by revoking all of its refresh-token families, used when
     * an administrator suspends or deactivates it (Requirement 19.2). Access tokens already issued
     * are not touched here: they are stateless and expire within the access TTL, and introspection
     * reports them inactive as soon as the account's status changes. Idempotent.
     */
    public void revokeAllRefreshTokens(String subject) {
        refreshTokenStore.revokeAllForSubject(subject);
    }

    /**
     * Parses and verifies a JWT access token, returning its claims. Throws a
     * {@code JwtException}/{@code IllegalArgumentException} subtype on any failure
     * (expired, bad signature, malformed).
     */
    public Claims parseAndVerify(String accessToken) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(accessToken)
                .getPayload();
    }

    /**
     * Introspects a token, returning a minimal claim map for API Gateway use.
     *
     * <p>This checks the token alone (signature, expiry). Whether the account behind it may still
     * authenticate is layered on by {@link IntrospectionService}, which is what the endpoint uses.
     *
     * @return {@code {active:true, sub, roles, exp, iss}} on success; {@code {active:false}}
     *         when the token is missing, expired, or otherwise invalid.
     */
    public Map<String, Object> introspect(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Map.of("active", false);
        }
        try {
            Claims claims = parseAndVerify(accessToken);
            Object roles = claims.get(ROLES_CLAIM);
            return Map.of(
                    "active", true,
                    "sub", claims.getSubject(),
                    ROLES_CLAIM, roles == null ? List.of() : roles,
                    "iss", claims.getIssuer() == null ? "" : claims.getIssuer(),
                    "exp", claims.getExpiration() == null ? 0L
                            : claims.getExpiration().toInstant().getEpochSecond());
        } catch (RuntimeException ex) {
            return Map.of("active", false);
        }
    }
}
