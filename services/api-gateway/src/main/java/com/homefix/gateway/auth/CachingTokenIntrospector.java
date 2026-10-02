package com.homefix.gateway.auth;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * {@link TokenIntrospector} decorator that briefly reuses <em>positive</em> introspection results
 * (Requirement 23.1), so an active client does not cost the Auth Service one call per request.
 *
 * <h2>What is cached, and for how long</h2>
 * <ul>
 *   <li><strong>Only active results.</strong> An inactive result — including the one a transport
 *       failure or timeout is mapped to — is given a lifetime of zero and evicted as soon as it
 *       completes, so the next request introspects again. A failure therefore still denies (fail
 *       closed) and can never be served as a cached success.</li>
 *   <li><strong>For at most {@code ttl}</strong> ({@code homefix.gateway.auth.introspection-cache.ttl},
 *       default 30 s), <strong>and never past the token's own {@code exp}</strong>. A result with no
 *       reported expiry, or one already expired by this gateway's clock, is not cached.</li>
 *   <li><strong>Keyed by SHA-256 of the token</strong>, never the token itself, so a heap dump of the
 *       gateway does not yield replayable bearer credentials.</li>
 *   <li><strong>Bounded</strong> to {@code max-entries} (default 10&nbsp;000, roughly a few MB);
 *       beyond that Caffeine evicts by frequency and recency.</li>
 * </ul>
 *
 * <h2>Trade-off: revocation latency</h2>
 * <p>For up to {@code ttl} after a token stops being valid at the Auth Service the gateway may still
 * admit it. Today that window costs nothing: access tokens are stateless JWTs that the Auth Service
 * cannot revoke before {@code exp} (logout revokes the refresh token only), so introspection of a
 * token that verified a moment ago gives the same answer until it expires — and expiry is honoured
 * exactly by the {@code exp} cap. Should access-token revocation be added, logout and role changes
 * take effect at the gateway within {@code ttl}; set it to {@code 0} to disable caching if that is
 * not acceptable. Every downstream service also verifies the JWT signature and expiry itself, so
 * the gateway cache can never extend a token's life past {@code exp} there either, whatever the clock
 * skew between gateway and Auth Service.
 *
 * <h2>Concurrent misses</h2>
 * <p>The cache holds the in-flight future, so concurrent requests carrying the same uncached token
 * share a single Auth Service call. Waiters subscribe with cancellation suppressed: one client
 * disconnecting does not cancel the shared call and fail the others.
 */
public class CachingTokenIntrospector implements TokenIntrospector {

    private static final Logger log = LoggerFactory.getLogger(CachingTokenIntrospector.class);

    private final TokenIntrospector delegate;
    private final AsyncCache<String, IntrospectionResult> cache;

    /**
     * @param delegate   the introspector actually asked on a miss (normally the HTTP adapter)
     * @param ttl        upper bound on how long an active result is reused; must be positive
     * @param maxEntries upper bound on the number of cached tokens; must be positive
     */
    public CachingTokenIntrospector(TokenIntrospector delegate, Duration ttl, long maxEntries) {
        this(delegate, ttl, maxEntries, Clock.systemUTC(), Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

    /** Test seam: deterministic time and synchronous cache maintenance. */
    CachingTokenIntrospector(TokenIntrospector delegate, Duration ttl, long maxEntries,
                             Clock clock, Ticker ticker, Executor executor) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Introspection cache ttl must be positive: " + ttl);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("Introspection cache max-entries must be positive: " + maxEntries);
        }
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfter(new PositiveResultExpiry(ttl, clock))
                .ticker(ticker)
                .executor(executor)
                .buildAsync();
    }

    @Override
    public Mono<IntrospectionResult> introspect(String token) {
        if (token == null || token.isEmpty()) {
            return delegate.introspect(token);
        }
        CompletableFuture<IntrospectionResult> shared = cache.get(cacheKey(token), (key, executor) ->
                Mono.defer(() -> delegate.introspect(token))
                        // The delegate contract is "never errors", but a failure here must never be
                        // anything other than a denial — and must not be cached (see PositiveResultExpiry).
                        .onErrorResume(err -> {
                            log.warn("Token introspection failed: {}", err.getClass().getSimpleName());
                            return Mono.just(IntrospectionResult.inactive());
                        })
                        .defaultIfEmpty(IntrospectionResult.inactive())
                        .toFuture());
        // suppressCancel: this future is shared by every concurrent caller with the same token.
        return Mono.fromFuture(shared, true)
                .onErrorResume(err -> Mono.just(IntrospectionResult.inactive()));
    }

    /** Hex SHA-256 of the token: the raw bearer credential is never held as a cache key. */
    static String cacheKey(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Visible for tests: the number of entries after pending maintenance has run. */
    long estimatedSize() {
        cache.synchronous().cleanUp();
        return cache.synchronous().estimatedSize();
    }

    /** Visible for tests: whether a live (unexpired) result is held for this token. */
    boolean isCached(String token) {
        return cache.synchronous().getIfPresent(cacheKey(token)) != null;
    }

    /**
     * Gives an active result the smaller of the configured ttl and the token's remaining lifetime,
     * and everything else a lifetime of zero, i.e. it is evicted the moment its load completes.
     */
    private static final class PositiveResultExpiry implements Expiry<String, IntrospectionResult> {

        private final long ttlNanos;
        private final Clock clock;

        private PositiveResultExpiry(Duration ttl, Clock clock) {
            this.ttlNanos = ttl.toNanos();
            this.clock = clock;
        }

        @Override
        public long expireAfterCreate(String key, IntrospectionResult value, long currentTime) {
            return lifetimeNanos(value);
        }

        @Override
        public long expireAfterUpdate(String key, IntrospectionResult value, long currentTime,
                                      long currentDuration) {
            return lifetimeNanos(value);
        }

        @Override
        public long expireAfterRead(String key, IntrospectionResult value, long currentTime,
                                    long currentDuration) {
            return currentDuration; // reads never extend a lifetime
        }

        private long lifetimeNanos(IntrospectionResult value) {
            if (value == null || !value.active() || value.expiresAt() == null) {
                return 0L;
            }
            Duration remaining = Duration.between(Instant.now(clock), value.expiresAt());
            if (remaining.isNegative() || remaining.isZero()) {
                return 0L;
            }
            return Math.min(ttlNanos, remaining.toNanos());
        }
    }
}
