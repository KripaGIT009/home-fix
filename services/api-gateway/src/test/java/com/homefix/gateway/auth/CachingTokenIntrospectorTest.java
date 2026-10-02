package com.homefix.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Behaviour of the gateway's introspection cache (Requirement 23.1; CODEBASE_REVIEW 8.2 "introspects
 * every request; no caching").
 *
 * <p>The security-relevant properties are pinned first: a failure or inactive answer is never
 * reused, a result is never reused past the token's own expiry or the configured ttl, and the raw
 * token is not the cache key. Time is driven by a fake clock/ticker and cache maintenance runs on
 * the calling thread, so nothing here sleeps.
 */
class CachingTokenIntrospectorTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final FakeTime time = new FakeTime();

    // ------------------------------------------------------------------ positive results

    @Test
    void activeResultIsReusedWithinTheTtl() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(active(time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        IntrospectionResult first = cache.introspect("token-a").block();
        time.advance(Duration.ofSeconds(29));
        IntrospectionResult second = cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(cache.isCached("token-a")).isTrue();
        assertThat(first.active()).isTrue();
        assertThat(second.subject()).isEqualTo("user-1");
        assertThat(second.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void activeResultIsNotReusedAfterTheTtl() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(active(time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        cache.introspect("token-a").block();
        time.advance(TTL.plusMillis(1));
        cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void lifetimeIsCappedByTheTokensOwnExpiry() {
        Instant exp = time.instant().plusSeconds(5);
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(active(exp)));
        CachingTokenIntrospector cache = cache(delegate, 100);

        cache.introspect("token-a").block();
        time.advance(Duration.ofSeconds(4));
        cache.introspect("token-a").block();
        assertThat(delegate.calls()).as("still inside exp").isEqualTo(1);

        time.advance(Duration.ofSeconds(2)); // now past exp, well inside the 30 s ttl
        cache.introspect("token-a").block();
        assertThat(delegate.calls()).as("re-introspected once exp passed").isEqualTo(2);
    }

    @Test
    void activeResultWithoutAnExpiryIsNotCached() {
        CountingIntrospector delegate = new CountingIntrospector(
                t -> Mono.just(new IntrospectionResult(true, "user-1", List.of("CUSTOMER"))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        cache.introspect("token-a").block();
        cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void activeResultAlreadyPastItsExpiryIsNotCached() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(active(time.instant().minusSeconds(1))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        cache.introspect("token-a").block();
        cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void distinctTokensAreCachedIndependently() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(
                new IntrospectionResult(true, "sub-" + t, List.of("CUSTOMER"), time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        assertThat(cache.introspect("token-a").block().subject()).isEqualTo("sub-token-a");
        assertThat(cache.introspect("token-b").block().subject()).isEqualTo("sub-token-b");
        assertThat(cache.introspect("token-a").block().subject()).isEqualTo("sub-token-a");

        assertThat(delegate.calls()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ negative results & failures

    @Test
    void inactiveResultIsNeverCached() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(IntrospectionResult.inactive()));
        CachingTokenIntrospector cache = cache(delegate, 100);

        assertThat(cache.introspect("token-a").block().active()).isFalse();
        assertThat(cache.introspect("token-a").block().active()).isFalse();

        assertThat(delegate.calls()).isEqualTo(2);
        assertThat(cache.isCached("token-a")).isFalse();
    }

    @Test
    void transportFailureDeniesAndDoesNotPoisonTheCache() {
        AtomicInteger attempt = new AtomicInteger();
        CountingIntrospector delegate = new CountingIntrospector(t -> attempt.incrementAndGet() == 1
                ? Mono.error(new IllegalStateException("connection refused"))
                : Mono.just(active(time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        assertThat(cache.introspect("token-a").block().active()).as("failure fails closed").isFalse();
        assertThat(cache.introspect("token-a").block().active()).as("recovery is seen at once").isTrue();
        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void transportFailureIsNotRetainedForLaterCallers() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.error(new IllegalStateException("timeout")));
        CachingTokenIntrospector cache = cache(delegate, 100);

        cache.introspect("token-a").block();
        cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(2);
        assertThat(cache.isCached("token-a")).isFalse();
    }

    @Test
    void delegateThrowingSynchronouslyDenies() {
        CachingTokenIntrospector cache = cache(t -> {
            throw new IllegalStateException("boom");
        }, 100);

        assertThat(cache.introspect("token-a").block().active()).isFalse();
    }

    @Test
    void emptyDelegateResponseDenies() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.empty());
        CachingTokenIntrospector cache = cache(delegate, 100);

        assertThat(cache.introspect("token-a").block().active()).isFalse();
        cache.introspect("token-a").block();
        assertThat(delegate.calls()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ key, bound, concurrency

    @Test
    void cacheKeyIsTheSha256OfTheTokenNotTheTokenItself() {
        // SHA-256("abc"), FIPS 180-2 test vector.
        assertThat(CachingTokenIntrospector.cacheKey("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.sig";
        assertThat(CachingTokenIntrospector.cacheKey(jwt)).doesNotContain("eyJ").hasSize(64);
    }

    @Test
    void entryCountIsBounded() {
        CountingIntrospector delegate = new CountingIntrospector(t -> Mono.just(active(time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 3);

        for (int i = 0; i < 50; i++) {
            cache.introspect("token-" + i).block();
        }

        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(3);
    }

    @Test
    void concurrentMissesForOneTokenShareASingleCall() throws Exception {
        Sinks.One<IntrospectionResult> pending = Sinks.one();
        CountingIntrospector delegate = new CountingIntrospector(t -> pending.asMono());
        CachingTokenIntrospector cache = cache(delegate, 100);

        CompletableFuture<IntrospectionResult> first = cache.introspect("token-a").toFuture();
        CompletableFuture<IntrospectionResult> second = cache.introspect("token-a").toFuture();
        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(first).isNotDone();

        pending.tryEmitValue(active(time.instant().plusSeconds(3600)));

        assertThat(first.get(1, TimeUnit.SECONDS).active()).isTrue();
        assertThat(second.get(1, TimeUnit.SECONDS).active()).isTrue();
        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void oneWaiterCancellingDoesNotFailTheOthers() throws Exception {
        Sinks.One<IntrospectionResult> pending = Sinks.one();
        CountingIntrospector delegate = new CountingIntrospector(t -> pending.asMono());
        CachingTokenIntrospector cache = cache(delegate, 100);

        Disposable disconnected = cache.introspect("token-a").subscribe();
        CompletableFuture<IntrospectionResult> survivor = cache.introspect("token-a").toFuture();
        disconnected.dispose(); // client went away mid-introspection

        pending.tryEmitValue(active(time.instant().plusSeconds(3600)));

        assertThat(survivor.get(1, TimeUnit.SECONDS).active()).isTrue();
        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void concurrentWaitersOnAFailedCallAllDenyAndTheNextCallerRetries() throws Exception {
        Sinks.One<IntrospectionResult> pending = Sinks.one();
        AtomicInteger attempt = new AtomicInteger();
        CountingIntrospector delegate = new CountingIntrospector(t -> attempt.incrementAndGet() == 1
                ? pending.asMono()
                : Mono.just(active(time.instant().plusSeconds(3600))));
        CachingTokenIntrospector cache = cache(delegate, 100);

        CompletableFuture<IntrospectionResult> first = cache.introspect("token-a").toFuture();
        CompletableFuture<IntrospectionResult> second = cache.introspect("token-a").toFuture();
        pending.tryEmitError(new IllegalStateException("auth-service down"));

        assertThat(first.get(1, TimeUnit.SECONDS).active()).isFalse();
        assertThat(second.get(1, TimeUnit.SECONDS).active()).isFalse();
        assertThat(cache.introspect("token-a").block().active()).isTrue();
        assertThat(delegate.calls()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ configuration guards

    @Test
    void rejectsANonPositiveTtlOrSize() {
        TokenIntrospector delegate = t -> Mono.just(IntrospectionResult.inactive());
        assertThatThrownBy(() -> new CachingTokenIntrospector(delegate, Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingTokenIntrospector(delegate, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingTokenIntrospector(delegate, TTL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionConstructorWorks() {
        CountingIntrospector delegate = new CountingIntrospector(
                t -> Mono.just(active(Instant.now().plusSeconds(3600))));
        CachingTokenIntrospector cache = new CachingTokenIntrospector(delegate, TTL, 100);

        cache.introspect("token-a").block();
        cache.introspect("token-a").block();

        assertThat(delegate.calls()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private CachingTokenIntrospector cache(TokenIntrospector delegate, long maxEntries) {
        return new CachingTokenIntrospector(delegate, TTL, maxEntries, time, time, Runnable::run);
    }

    private static IntrospectionResult active(Instant exp) {
        return new IntrospectionResult(true, "user-1", List.of("CUSTOMER"), exp);
    }

    /** Counts how many times the Auth Service would have been called. */
    private static final class CountingIntrospector implements TokenIntrospector {
        private final Function<String, Mono<IntrospectionResult>> behaviour;
        private final AtomicInteger calls = new AtomicInteger();

        CountingIntrospector(Function<String, Mono<IntrospectionResult>> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Mono<IntrospectionResult> introspect(String token) {
            calls.incrementAndGet();
            return behaviour.apply(token);
        }

        int calls() {
            return calls.get();
        }
    }

    /** One time source for both the cache's ticker and the expiry computation's clock. */
    private static final class FakeTime extends Clock implements Ticker {
        private final Instant base = Instant.parse("2026-10-02T00:00:00Z");
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }

        @Override
        public Instant instant() {
            return base.plusNanos(nanos.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
