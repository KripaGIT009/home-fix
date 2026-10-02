package com.homefix.auth.token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off removal, at startup, of refresh tokens stored in clear by earlier versions of
 * {@link RedisRefreshTokenStore}.
 *
 * <p>Those versions keyed each token as {@code auth:refresh:{token}} and listed the raw tokens in
 * {@code auth:family:{familyId}}. The store now keys by {@code sha256(token)}, so the old entries
 * can never be found again, yet each kept a live bearer credential readable by anyone with access
 * to Redis (a dump, a replica, a {@code KEYS}) for up to the 30-day refresh TTL. Removing them
 * ends those sessions, which is the price of the hashing change: holders of a pre-upgrade refresh
 * token sign in again (their refresh already fails, since the token is no longer found).
 *
 * <p><b>Telling the formats apart.</b> Refresh tokens have always been two concatenated
 * {@code UUID.toString()} values: 72 characters, lower-case hex in 8-4-4-4-12 groups, with
 * hyphens. A hashed key ends in exactly 64 lower-case hex characters and never has a hyphen, so
 * {@link #LEGACY_TOKEN} cannot match one. Family sets keep their key name across the change; only
 * their legacy (raw-token) members are removed, and Redis drops a set once it is empty. The
 * revoked-family markers ({@code auth:family-revoked:*}) do not match the {@code auth:family:*}
 * pattern and are never touched.
 *
 * <p>Iterates with {@code SCAN}, never {@code KEYS}, so it does not block Redis. Idempotent and
 * safe to run on every instance and every start: once nothing legacy remains it only scans.
 * A failure is logged and swallowed; the cleanup must never keep the service from starting, and
 * the leftovers expire on their own within the refresh TTL. Disable with
 * {@code homefix.auth.legacy-refresh-cleanup.enabled=false} once every environment has run it.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.auth.legacy-refresh-cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LegacyRefreshTokenCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyRefreshTokenCleanup.class);

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** A refresh token as every version has minted it: two concatenated UUIDs. */
    static final Pattern LEGACY_TOKEN = Pattern.compile(UUID_PATTERN + UUID_PATTERN);

    static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redis;

    public LegacyRefreshTokenCleanup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Result result = cleanUp();
            if (result.tokenKeys() > 0 || result.familyMembers() > 0) {
                log.info("Removed {} legacy cleartext refresh-token keys and {} legacy family members;"
                        + " their sessions must sign in again", result.tokenKeys(), result.familyMembers());
            } else {
                log.debug("No legacy cleartext refresh tokens found");
            }
        } catch (RuntimeException ex) {
            log.warn("Legacy refresh-token cleanup failed; leftovers expire within the refresh TTL: {}",
                    ex.toString());
        }
    }

    /** Runs the cleanup once; visible for tests. */
    Result cleanUp() {
        long tokenKeys = deleteLegacyTokenKeys();
        long familyMembers = removeLegacyFamilyMembers();
        return new Result(tokenKeys, familyMembers);
    }

    private long deleteLegacyTokenKeys() {
        long deleted = 0;
        List<String> batch = new ArrayList<>(SCAN_BATCH);
        try (Cursor<String> keys = redis.scan(scan(RedisRefreshTokenStore.TOKEN_PREFIX))) {
            while (keys.hasNext()) {
                String key = keys.next();
                if (isLegacyToken(key.substring(RedisRefreshTokenStore.TOKEN_PREFIX.length()))) {
                    batch.add(key);
                    if (batch.size() == SCAN_BATCH) {
                        deleted += delete(batch);
                    }
                }
            }
        }
        return deleted + delete(batch);
    }

    private long removeLegacyFamilyMembers() {
        long removed = 0;
        try (Cursor<String> families = redis.scan(scan(RedisRefreshTokenStore.FAMILY_PREFIX))) {
            while (families.hasNext()) {
                String family = families.next();
                if (redis.type(family) != DataType.SET) {
                    continue;
                }
                Set<String> members = redis.opsForSet().members(family);
                if (members == null) {
                    continue;
                }
                Object[] legacy = members.stream().filter(LegacyRefreshTokenCleanup::isLegacyToken).toArray();
                if (legacy.length > 0) {
                    Long count = redis.opsForSet().remove(family, legacy);
                    removed += count == null ? 0 : count;
                }
            }
        }
        return removed;
    }

    private long delete(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        Long count = redis.delete(List.copyOf(keys));
        keys.clear();
        return count == null ? 0 : count;
    }

    private static ScanOptions scan(String prefix) {
        return ScanOptions.scanOptions().match(prefix + "*").count(SCAN_BATCH).build();
    }

    static boolean isLegacyToken(String suffix) {
        return LEGACY_TOKEN.matcher(suffix).matches();
    }

    /** What one run removed. */
    record Result(long tokenKeys, long familyMembers) {
    }
}
