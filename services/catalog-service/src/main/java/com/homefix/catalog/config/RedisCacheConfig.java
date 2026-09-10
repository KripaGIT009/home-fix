package com.homefix.catalog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Wires a String-keyed/String-valued {@link RedisTemplate} for the catalog read-through cache
 * (Requirement 3.8). Only active when {@code homefix.catalog.cache=redis} so the in-memory
 * adapter remains the zero-dependency default for local/dev and tests.
 */
@Configuration
@ConditionalOnProperty(name = "homefix.catalog.cache", havingValue = "redis")
public class RedisCacheConfig {

    @Bean
    public RedisTemplate<String, String> catalogRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
