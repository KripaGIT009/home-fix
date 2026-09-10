package com.homefix.booking.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a system {@link Clock} so that time-dependent logic (lead-time and horizon
 * validation, Requirement 7.7-7.8) can be driven by a fixed clock in tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
