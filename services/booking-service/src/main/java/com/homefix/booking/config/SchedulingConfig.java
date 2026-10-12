package com.homefix.booking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the Booking Service's sweepers: the Tenant assignment timeout
 * (Requirement MT-7.3), the additional-quote approval timeout (Requirement 9.9) and the stalled
 * provider search (review 17.5 item 4). {@code homefix.booking.scheduling.enabled=false} switches
 * them off, for a test context that drives a sweeper itself.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "homefix.booking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
