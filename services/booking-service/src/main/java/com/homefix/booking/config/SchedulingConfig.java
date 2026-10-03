package com.homefix.booking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the Booking Service, whose only scheduled job is the Tenant
 * assignment timeout sweeper (Requirement MT-7.3). {@code homefix.booking.scheduling.enabled=false}
 * switches it off, for a test context that drives the sweeper itself.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "homefix.booking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
