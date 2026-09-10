package com.homefix.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.homefix.payment.idempotency.IdempotencyStorePort;
import com.homefix.payment.idempotency.RedisIdempotencyStoreAdapter;

/**
 * Wires infrastructure-backed port adapters that need explicit construction.
 *
 * <ul>
 *   <li>{@link IdempotencyStorePort} &rarr; Redis adapter by default; disable with
 *       {@code homefix.payment.idempotency-store=memory} (tests supply their own in-memory fake).</li>
 * </ul>
 *
 * <p>Gateway adapters, the KMS adapter, wallet/invoice/alert/notification adapters, and the
 * outbox publisher are component-scanned, so registering a new payment gateway is purely additive
 * (Requirement 12.1).
 */
@Configuration
public class PaymentAdaptersConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "homefix.payment", name = "idempotency-store", havingValue = "redis",
            matchIfMissing = true)
    public IdempotencyStorePort idempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStoreAdapter(redisTemplate);
    }
}
