package com.homefix.catalog.cache;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.config.CatalogProperties;

/**
 * Redis-backed {@link CatalogCachePort} implementing the 300-second read-through TTL
 * (Requirement 3.8). Selected when {@code homefix.catalog.cache=redis}.
 *
 * <p>The catalog view is serialised as JSON so it survives a service restart and is shared
 * across replicas. Deserialisation failures are treated as a cache miss so a corrupt entry can
 * never break customer reads.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.cache", havingValue = "redis")
public class RedisCatalogCacheAdapter implements CatalogCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisCatalogCacheAdapter.class);
    private static final TypeReference<List<CategoryView>> LIST_TYPE = new TypeReference<>() {
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final CatalogProperties properties;

    public RedisCatalogCacheAdapter(RedisTemplate<String, String> redisTemplate,
                                    ObjectMapper objectMapper,
                                    CatalogProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<List<CategoryView>> getActiveCatalog() {
        String json = redisTemplate.opsForValue().get(ACTIVE_CATALOG_KEY);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, LIST_TYPE));
        } catch (Exception ex) {
            log.warn("Discarding unreadable catalog cache entry; treating as miss");
            return Optional.empty();
        }
    }

    @Override
    public void putActiveCatalog(List<CategoryView> catalog) {
        try {
            String json = objectMapper.writeValueAsString(catalog);
            redisTemplate.opsForValue().set(ACTIVE_CATALOG_KEY, json, properties.getCacheTtl());
        } catch (Exception ex) {
            // Caching is best-effort; a write failure must not break the request.
            log.warn("Failed to write catalog cache entry; serving without cache");
        }
    }

    @Override
    public void invalidate() {
        redisTemplate.delete(ACTIVE_CATALOG_KEY);
    }
}
