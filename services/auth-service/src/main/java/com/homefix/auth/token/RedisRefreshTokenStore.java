package com.homefix.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.homefix.auth.config.AuthTokenProperties;

/**
 * Redis-backed {@link RefreshTokenStore}.
 *
 * <p>Each refresh token is stored as a triple encoded in a single value
 * ({@code subject|familyId|used}) under {@code auth:refresh:{sha256(token)}} with a 30-day TTL. A
 * companion set {@code auth:family:{familyId}} tracks the hash of every token minted in the
 * family so {@link #revokeFamily(String)} can delete them all (Property 26). A second index,
 * {@code auth:subject-families:{subject}}, lists the families each account holds so
 * {@link #revokeAllForSubject(String)} can end every session of a suspended account. Both indexes
 * share the tokens' TTL, renewed on every save.
 *
 * <p><b>Tokens are never stored in clear.</b> Keys and family members carry the SHA-256 of the
 * token, so a read of Redis (a dump, a {@code KEYS} scan, a replica) yields nothing that can be
 * presented to {@code /auth/token/refresh}. The tokens are 244 bits of randomness, so an unsalted
 * fast hash is sufficient; there is nothing to brute-force.
 *
 * <p><b>Rotation is atomic.</b> {@link #consume} is a single Lua script: it reads the record and,
 * if unused, flips it to used in the same server-side step, returning the prior value. Redis runs
 * scripts serially, so two concurrent refreshes of one token cannot both observe it unused —
 * the second sees {@code used} and is treated as a replay. The earlier GET-check-SET sequence
 * across three round trips let both succeed (CODEBASE_REVIEW.md 8.2).
 *
 * <p><b>Revocation is sticky.</b> {@link #revokeFamily} first writes a revoked-family marker and
 * only then deletes the members. {@link #save} is a script that refuses to write into a family
 * carrying the marker. Together these close the window in which a rotation that won the race
 * could otherwise store its successor <em>after</em> a concurrent replay had already revoked the
 * family: any save ordered after the marker is refused, and any save ordered before it is a
 * member by the time the members are read and deleted.
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String TOKEN_PREFIX = "auth:refresh:";
    static final String FAMILY_PREFIX = "auth:family:";
    static final String REVOKED_FAMILY_PREFIX = "auth:family-revoked:";
    static final String SUBJECT_FAMILIES_PREFIX = "auth:subject-families:";
    private static final String SEP = "|";

    /**
     * KEYS[1] = token key; ARGV[1] = TTL in milliseconds. Returns the value as it was before the
     * call (nil when absent) and, if it was unused, rewrites it as used with the given TTL.
     */
    static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return false
            end
            if string.sub(current, -5) == '|true' then
              return current
            end
            local base = current
            if string.sub(current, -6) == '|false' then
              base = string.sub(current, 1, -7)
            end
            redis.call('SET', KEYS[1], base .. '|true', 'PX', ARGV[1])
            return current
            """, String.class);

    /**
     * KEYS[1] = token key, KEYS[2] = family index, KEYS[3] = revoked-family marker,
     * KEYS[4] = subject's family index; ARGV[1] = encoded record, ARGV[2] = TTL in milliseconds,
     * ARGV[3] = token hash, ARGV[4] = family id.
     * Returns 1 when stored, 0 when the family has been revoked.
     */
    static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            redis.call('SADD', KEYS[4], ARGV[4])
            redis.call('PEXPIRE', KEYS[4], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration revokedMarkerTtl;

    public RedisRefreshTokenStore(StringRedisTemplate redis, AuthTokenProperties tokenProperties) {
        this.redis = redis;
        // A revoked family's marker must outlive any member that could still be presented, and
        // no member outlives the refresh TTL.
        this.revokedMarkerTtl = tokenProperties.getRefreshTtl();
    }

    @Override
    public boolean save(String token, String subject, String familyId, Duration ttl) {
        String hash = hash(token);
        Long stored = redis.execute(SAVE_SCRIPT,
                List.of(TOKEN_PREFIX + hash, FAMILY_PREFIX + familyId, REVOKED_FAMILY_PREFIX + familyId,
                        SUBJECT_FAMILIES_PREFIX + subject),
                encode(subject, familyId, false), Long.toString(ttl.toMillis()), hash, familyId);
        return stored != null && stored == 1L;
    }

    @Override
    public Optional<RefreshTokenRecord> find(String token) {
        String raw = redis.opsForValue().get(TOKEN_PREFIX + hash(token));
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(decode(raw));
    }

    @Override
    public Optional<RefreshTokenRecord> consume(String token, Duration ttl) {
        String prior = redis.execute(CONSUME_SCRIPT,
                List.of(TOKEN_PREFIX + hash(token)), Long.toString(ttl.toMillis()));
        return prior == null ? Optional.empty() : Optional.of(decode(prior));
    }

    @Override
    public void revoke(String token) {
        redis.delete(TOKEN_PREFIX + hash(token));
    }

    @Override
    public void revokeFamily(String familyId) {
        // Marker first: from here on no successor can be saved into the family (see class doc).
        redis.opsForValue().set(REVOKED_FAMILY_PREFIX + familyId, "1", revokedMarkerTtl);
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> members = redis.opsForSet().members(familyKey);
        if (members != null) {
            for (String tokenHash : members) {
                redis.delete(TOKEN_PREFIX + tokenHash);
            }
        }
        redis.delete(familyKey);
    }

    @Override
    public void revokeAllForSubject(String subject) {
        String subjectKey = SUBJECT_FAMILIES_PREFIX + subject;
        Set<String> families = redis.opsForSet().members(subjectKey);
        if (families == null || families.isEmpty()) {
            return;
        }
        // Each family is revoked marker-first, so a rotation racing this cannot store a live
        // successor into it (see class doc).
        families.forEach(this::revokeFamily);
        // Remove only what was revoked: a family a concurrent sign-in added after the read above
        // stays indexed, so a later revocation still finds it.
        redis.opsForSet().remove(subjectKey, families.toArray());
    }

    /** SHA-256 of the raw token, lower-case hex. Visible for tests that assert the key layout. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String encode(String subject, String familyId, boolean used) {
        return subject + SEP + familyId + SEP + used;
    }

    private static RefreshTokenRecord decode(String raw) {
        String[] parts = raw.split("\\|", 3);
        boolean used = parts.length > 2 && Boolean.parseBoolean(parts[2]);
        return new RefreshTokenRecord(parts[0], parts[1], used);
    }
}
