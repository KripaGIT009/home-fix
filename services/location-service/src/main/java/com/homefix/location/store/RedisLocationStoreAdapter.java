package com.homefix.location.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.location.config.LocationProperties;
import com.homefix.location.domain.CachedLocation;
import com.homefix.location.domain.Coordinates;

/**
 * Redis-backed {@link LocationStorePort} keyed by {@code location:{bookingId}:{providerId}}
 * (design data flow). The last known location for a Booking is stored as a small JSON document
 * with a TTL, plus a per-Booking pointer key so the tracking view can look up the latest
 * location without knowing the Provider id in advance.
 *
 * <p>Active by default; disabled in tests via {@code homefix.location.store=memory} so the core
 * logic runs against the in-memory fake.
 */
public class RedisLocationStoreAdapter implements LocationStorePort {

    private static final String LATEST_PROVIDER_KEY_PREFIX = "location:latest:";
    private static final String LOCATION_KEY_PREFIX = "location:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisLocationStoreAdapter(StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     LocationProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = properties.getCacheTtl();
    }

    @Override
    public void save(CachedLocation location) {
        String payload = serialize(location);
        redis.opsForValue().set(locationKey(location.bookingId(), location.providerId()), payload, ttl);
        // Pointer to the Provider currently reporting for this Booking so findLatest works.
        redis.opsForValue().set(latestProviderKey(location.bookingId()),
                location.providerId().toString(), ttl);
    }

    @Override
    public Optional<CachedLocation> findLatest(UUID bookingId) {
        String providerId = redis.opsForValue().get(latestProviderKey(bookingId));
        if (providerId == null) {
            return Optional.empty();
        }
        String payload = redis.opsForValue().get(locationKey(bookingId, UUID.fromString(providerId)));
        if (payload == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(payload));
    }

    @Override
    public void evict(UUID bookingId) {
        String providerId = redis.opsForValue().get(latestProviderKey(bookingId));
        if (providerId != null) {
            redis.delete(locationKey(bookingId, UUID.fromString(providerId)));
        }
        redis.delete(latestProviderKey(bookingId));
    }

    private String locationKey(UUID bookingId, UUID providerId) {
        return LOCATION_KEY_PREFIX + bookingId + ":" + providerId;
    }

    private String latestProviderKey(UUID bookingId) {
        return LATEST_PROVIDER_KEY_PREFIX + bookingId;
    }

    private String serialize(CachedLocation location) {
        try {
            return objectMapper.writeValueAsString(new StoredLocation(
                    location.bookingId().toString(),
                    location.providerId().toString(),
                    location.coordinates().latitude(),
                    location.coordinates().longitude(),
                    location.recordedAt().toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cached location", e);
        }
    }

    private CachedLocation deserialize(String payload) {
        try {
            StoredLocation stored = objectMapper.readValue(payload, StoredLocation.class);
            return new CachedLocation(
                    UUID.fromString(stored.bookingId()),
                    UUID.fromString(stored.providerId()),
                    new Coordinates(stored.latitude(), stored.longitude()),
                    Instant.parse(stored.recordedAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached location", e);
        }
    }

    /** JSON shape stored in Redis. */
    private record StoredLocation(String bookingId, String providerId,
                                  double latitude, double longitude, String recordedAt) {
    }
}
