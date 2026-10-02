package com.homefix.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.gateway.auth.CachingTokenIntrospector;
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
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

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

    /**
     * The connection pool behind {@link #gatewayWebClient}, separate from Reactor Netty's global
     * one so that waiting for a free connection is bounded by the introspection timeout too. With
     * the global pool's default a request queued behind a saturated pool waited 45 s for a
     * connection before {@code responseTimeout} even started counting.
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider introspectionConnectionProvider(GatewaySecurityProperties properties) {
        return ConnectionProvider.builder("auth-introspection")
                .pendingAcquireTimeout(properties.getAuth().getIntrospectTimeout())
                .build();
    }

    @Bean
    public WebClient gatewayWebClient(GatewaySecurityProperties properties,
                                      ConnectionProvider introspectionConnectionProvider) {
        // Reactor Netty's default DNS resolver caches by the TTL of the record it was given,
        // and Docker's embedded DNS hands out 600 seconds. Recreating auth-service therefore
        // leaves the gateway introspecting a dead address for ten minutes, and because a
        // transport failure is deliberately mapped to "inactive", the whole platform answers
        // 401 with nothing obviously broken. The JDK resolver honours
        // networkaddress.cache.ttl instead, which is 30s by default and set lower below.
        //
        // responseTimeout only starts once the request has been written. Acquiring a pooled
        // connection (bounded by the provider above) and opening a new one (CONNECT_TIMEOUT_MILLIS,
        // 30 s by default, longer for an unanswered SYN) come before it, so each gets the same
        // bound here; WebClientTokenIntrospector also caps the whole exchange.
        Duration timeout = properties.getAuth().getIntrospectTimeout();
        HttpClient httpClient = HttpClient.create(introspectionConnectionProvider)
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(Integer.MAX_VALUE, timeout.toMillis()))
                .responseTimeout(timeout);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * The HTTP introspector, wrapped in a short-TTL cache of positive results unless
     * {@code homefix.gateway.auth.introspection-cache.ttl} is {@code 0}. Without the cache every
     * authenticated request on the platform costs one Auth Service call.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenIntrospector tokenIntrospector(WebClient gatewayWebClient,
                                               GatewaySecurityProperties properties) {
        TokenIntrospector remote = new WebClientTokenIntrospector(gatewayWebClient, properties);
        GatewaySecurityProperties.IntrospectionCache cache = properties.getAuth().getIntrospectionCache();
        if (!cache.isEnabled()) {
            return remote;
        }
        return new CachingTokenIntrospector(remote, cache.getTtl(), cache.getMaxEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitCounter rateLimitCounter(ReactiveStringRedisTemplate redisTemplate) {
        return new RedisRateLimitCounter(redisTemplate);
    }
}
