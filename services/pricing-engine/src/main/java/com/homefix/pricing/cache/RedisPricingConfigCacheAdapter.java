package com.homefix.pricing.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Redis-backed {@link PricingConfigCachePort} (Requirement 6.11), active only when
 * {@code homefix.pricing.cache=redis} so the in-memory adapter remains the zero-dependency
 * default for local/dev and tests.
 *
 * <p>Parameters are stored as JSON with the configured TTL so Redis expires stale entries
 * automatically; Admin updates additionally delete the key so the next read repopulates from
 * the source of truth, keeping new bookings within the 60-second propagation bound.
 */
@Component
@ConditionalOnProperty(name = "homefix.pricing.cache", havingValue = "redis")
public class RedisPricingConfigCacheAdapter implements PricingConfigCachePort {

    private static final String KEY_PREFIX = "pricing:params:";

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final PricingProperties properties;

    public RedisPricingConfigCacheAdapter(RedisTemplate<String, String> redis,
                                          ObjectMapper objectMapper,
                                          PricingProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private static String key(UUID subcategoryId) {
        return KEY_PREFIX + subcategoryId;
    }

    @Override
    public Optional<PricingParameters> get(UUID subcategoryId) {
        String json = redis.opsForValue().get(key(subcategoryId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PricingParameters.class));
        } catch (Exception e) {
            // Treat an undeserializable entry as a miss rather than failing the price request.
            return Optional.empty();
        }
    }

    @Override
    public void put(PricingParameters parameters) {
        Duration ttl = properties.getConfigCacheTtl();
        try {
            String json = objectMapper.writeValueAsString(parameters);
            redis.opsForValue().set(key(parameters.subcategoryId()), json, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pricing parameters for cache", e);
        }
    }

    @Override
    public void invalidate(UUID subcategoryId) {
        redis.delete(key(subcategoryId));
    }
}
