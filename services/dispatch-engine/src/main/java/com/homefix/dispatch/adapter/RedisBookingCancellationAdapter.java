package com.homefix.dispatch.adapter;

import com.homefix.dispatch.port.BookingCancellationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed {@link BookingCancellationPort}: {@code dispatch:cancelled:{bookingId}} is set when a
 * booking is cancelled. The key only has to outlive the longest possible dispatch run (every radius
 * cycle times every candidate's offer window), so it expires after {@link #TTL} rather than
 * accumulating forever.
 *
 * <p>Registered by {@code DispatchAdaptersConfig}, for the same reason as
 * {@link RedisDistributedLockAdapter}.
 */
public class RedisBookingCancellationAdapter implements BookingCancellationPort {

    private static final Logger log = LoggerFactory.getLogger(RedisBookingCancellationAdapter.class);

    static final String KEY_PREFIX = "dispatch:cancelled:";

    /** Comfortably longer than any dispatch run. */
    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public RedisBookingCancellationAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void markCancelled(UUID bookingId) {
        redis.opsForValue().set(KEY_PREFIX + bookingId, "1", TTL);
    }

    @Override
    public boolean isCancelled(UUID bookingId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + bookingId));
        } catch (RuntimeException e) {
            // Fail open: a Redis hiccup must not abandon a booking that is still searching. A real
            // cancellation is still caught by the Booking Service's 409 on the final transition.
            log.warn("Could not check cancellation of booking {}; continuing the search: {}",
                    bookingId, e.toString());
            return false;
        }
    }
}
