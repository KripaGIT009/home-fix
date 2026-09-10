package com.homefix.auth.token;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link RefreshTokenStore}.
 *
 * <p>Each refresh token is stored as a hash-like triple encoded in a single value
 * ({@code subject|familyId|used}) under {@code auth:refresh:{token}} with a 30-day TTL. A
 * companion set {@code auth:family:{familyId}} tracks every token minted in the family so
 * {@link #revokeFamily(String)} can delete them all in one pass (Property 26).
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String TOKEN_PREFIX = "auth:refresh:";
    static final String FAMILY_PREFIX = "auth:family:";
    private static final String SEP = "|";

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String token, String subject, String familyId, Duration ttl) {
        redis.opsForValue().set(TOKEN_PREFIX + token, encode(subject, familyId, false), ttl);
        redis.opsForSet().add(FAMILY_PREFIX + familyId, token);
        // Keep the family index alive at least as long as its longest-lived member.
        redis.expire(FAMILY_PREFIX + familyId, ttl);
    }

    @Override
    public Optional<RefreshTokenRecord> find(String token) {
        String raw = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(decode(raw));
    }

    @Override
    public void markUsed(String token, Duration ttl) {
        String raw = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (raw == null) {
            return;
        }
        RefreshTokenRecord current = decode(raw);
        redis.opsForValue().set(TOKEN_PREFIX + token,
                encode(current.subject(), current.familyId(), true), ttl);
    }

    @Override
    public void revoke(String token) {
        redis.delete(TOKEN_PREFIX + token);
    }

    @Override
    public void revokeFamily(String familyId) {
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> members = redis.opsForSet().members(familyKey);
        if (members != null) {
            for (String token : members) {
                redis.delete(TOKEN_PREFIX + token);
            }
        }
        redis.delete(familyKey);
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
