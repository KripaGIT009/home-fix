package com.homefix.promotion.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires supporting beans for the Promotion / Coupon Service.
 *
 * <p>Exposes a system {@link Clock} so date/validity-window checks (Requirement 21.2) use an
 * injectable time source that tests can substitute with a fixed clock.
 */
@Configuration
public class PromotionBeansConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
