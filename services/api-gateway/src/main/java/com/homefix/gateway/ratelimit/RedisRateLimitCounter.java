package com.homefix.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive-Redis backed {@link RateLimitCounter}. Uses {@code INCR} and sets the window TTL only on
 * the first increment (count == 1) so the fixed window resets on its own.
 */
public class RedisRateLimitCounter implements RateLimitCounter {

    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisRateLimitCounter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Long> incrementAndGet(String key, Duration window) {
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        return redisTemplate.expire(key, window).thenReturn(count);
                    }
                    return Mono.just(count);
                });
    }
}
