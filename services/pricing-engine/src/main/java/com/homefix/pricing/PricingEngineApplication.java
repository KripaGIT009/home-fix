package com.homefix.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.pricing.config.PricingProperties;

/**
 * Entry point for the HomeFix Pricing Engine.
 *
 * <p>Owns itemized price calculation, emergency/surge multiplier application, night/weekend
 * surcharges, distance charging, coupon validation, provider-specific overrides, and
 * Admin-configurable pricing parameters cached in Redis (Requirement 6). The shared security,
 * observability, and outbox libraries are wired automatically via their Spring Boot
 * auto-configurations simply by being on the classpath (Tasks 4-6).
 *
 * <p>Pricing parameters, coupons and overrides are served through mockable ports whose
 * production adapters persist via the datasource once it is provisioned.
 */
@SpringBootApplication
@EnableConfigurationProperties(PricingProperties.class)
public class PricingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingEngineApplication.class, args);
    }
}
