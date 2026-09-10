package com.homefix.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.gateway.auth.TokenIntrospector;
import com.homefix.gateway.auth.WebClientTokenIntrospector;
import com.homefix.gateway.ratelimit.RateLimitCounter;
import com.homefix.gateway.ratelimit.RateLimitPolicy;
import com.homefix.gateway.ratelimit.RedisRateLimitCounter;
import com.homefix.gateway.support.GatewayErrorWriter;
import com.homefix.gateway.waf.AttackPatternMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the gateway's collaborators. Core logic beans ({@link RateLimitPolicy},
 * {@link AttackPatternMatcher}) are plain objects so they can also be unit-tested in isolation.
 * The Redis-backed counter and WebClient introspector are declared {@code @ConditionalOnMissingBean}
 * so tests can substitute in-memory/stub implementations.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayBeansConfig {

    @Bean
    public RateLimitPolicy rateLimitPolicy(GatewaySecurityProperties properties) {
        GatewaySecurityProperties.RateLimit rl = properties.getRateLimit();
        return new RateLimitPolicy(
                rl.getCustomerPerMinute(),
                rl.getProviderPerMinute(),
                rl.getDefaultPerMinute());
    }

    @Bean
    public AttackPatternMatcher attackPatternMatcher() {
        return new AttackPatternMatcher();
    }

    @Bean
    public GatewayErrorWriter gatewayErrorWriter(ObjectMapper objectMapper) {
        return new GatewayErrorWriter(objectMapper);
    }

    @Bean
    public WebClient gatewayWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenIntrospector tokenIntrospector(WebClient gatewayWebClient,
                                               GatewaySecurityProperties properties) {
        return new WebClientTokenIntrospector(gatewayWebClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitCounter rateLimitCounter(ReactiveStringRedisTemplate redisTemplate) {
        return new RedisRateLimitCounter(redisTemplate);
    }
}
