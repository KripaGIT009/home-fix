package com.homefix.location.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a system {@link Clock} so that time-dependent logic — the 1-per-5 s rate limit
 * (Requirement 10.1) and the 60 s staleness window (Requirement 10.7) — can be driven by a
 * fixed clock in tests for deterministic behaviour.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
