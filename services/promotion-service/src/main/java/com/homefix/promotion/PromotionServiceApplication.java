package com.homefix.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.homefix.promotion.config.PromotionProperties;

/**
 * Entry point for the HomeFix Promotion / Coupon Service.
 *
 * <p>Owns coupon and promotion management (Requirement 21): Admin coupon CRUD with attribute
 * validation, a checkout-time validation endpoint that returns the applicable discount or a
 * descriptive constraint violation, atomic redemption that increments the total and per-user
 * usage counters without over-redemption under concurrency (Property 20), immediate Admin
 * deactivation, and atomic counter decrement on cancellation before payment capture (Property 21).
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence package are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(PromotionProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.promotion", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.promotion", "com.homefix.shared.outbox"})
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
