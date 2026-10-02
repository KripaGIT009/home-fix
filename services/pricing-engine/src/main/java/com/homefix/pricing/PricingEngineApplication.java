package com.homefix.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.config.PromotionClientProperties;

/**
 * Entry point for the HomeFix Pricing Engine.
 *
 * <p>Owns itemized price calculation, emergency/surge multiplier application, night/weekend
 * surcharges, distance charging, coupon discounts, provider-specific overrides, and
 * Admin-configurable pricing parameters cached in Redis (Requirement 6). The shared security,
 * observability, and outbox libraries are wired automatically via their Spring Boot
 * auto-configurations simply by being on the classpath (Tasks 4-6).
 *
 * <p>Pricing parameters persist in the {@code pricing} schema through a JPA adapter. Coupons are
 * owned by the Promotion Service, which quotes their discounts over its internal API. Both sit
 * behind mockable ports. Provider overrides are validated against the parameters and stored
 * nowhere. Entity and repository scanning stays on this package only, so the shared outbox
 * entities, which this service does not use, are not mapped.
 */
@SpringBootApplication
@EnableConfigurationProperties({PricingProperties.class, PromotionClientProperties.class})
public class PricingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingEngineApplication.class, args);
    }
}
