package com.homefix.pricing.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Unit tests for the Redis-backed pricing-config cache (Requirement 6.11). A mocked
 * {@link RedisTemplate} lets us assert the key shape, JSON round-trip, TTL, and the
 * miss-on-undeserializable-entry behaviour without a real Redis.
 */
@ExtendWith(MockitoExtension.class)
class RedisPricingConfigCacheAdapterTest {

    private static final String KEY = "pricing:params:" + SUBCATEGORY;

    @Mock private RedisTemplate<String, String> redis;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PricingProperties properties = new PricingProperties();

    private RedisPricingConfigCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisPricingConfigCacheAdapter(redis, mapper, properties);
    }

    @Test
    void getReturnsEmptyOnMiss() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(adapter.get(SUBCATEGORY)).isEmpty();
    }

    @Test
    void getDeserializesStoredJson() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        String json = mapper.writeValueAsString(baseParams("123.00"));
        when(valueOps.get(KEY)).thenReturn(json);

        PricingParameters got = adapter.get(SUBCATEGORY).orElseThrow();
        assertThat(got.basePrice()).isEqualByComparingTo("123.00");
    }

    @Test
    void getTreatsUndeserializableEntryAsMiss() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("{not-json");

        assertThat(adapter.get(SUBCATEGORY)).isEmpty();
    }

    @Test
    void putSerializesWithConfiguredTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);

        adapter.put(baseParams("100.00"));

        verify(valueOps).set(eq(KEY), any(String.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void invalidateDeletesKey() {
        adapter.invalidate(SUBCATEGORY);

        verify(redis).delete(KEY);
    }
}
