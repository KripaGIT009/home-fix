package com.homefix.dispatch.config;

import com.homefix.dispatch.adapter.RedisDistributedLockAdapter;
import com.homefix.dispatch.port.DistributedLockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers the outbound adapters that depend on auto-configured infrastructure.
 *
 * <p>The exclusive per-provider offer lock (Requirement 8.11) is what stops the same provider
 * being offered two bookings at once, so the Dispatch Engine must not start without it: this bean
 * takes the auto-configured {@link StringRedisTemplate} as a constructor parameter, and a missing
 * Redis connection therefore fails startup loudly instead of quietly disabling locking.
 */
@Configuration
public class DispatchAdaptersConfig {

    @Bean
    @ConditionalOnMissingBean(DistributedLockPort.class)
    public DistributedLockPort distributedLockPort(StringRedisTemplate redisTemplate) {
        return new RedisDistributedLockAdapter(redisTemplate);
    }
}
