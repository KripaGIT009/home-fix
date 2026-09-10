package com.homefix.dispatch;

import com.homefix.dispatch.config.DispatchBulkheadProperties;
import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.config.DispatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the HomeFix Dispatch Engine.
 *
 * <p>Consumes {@code BookingCreated}, matches and ranks eligible providers with a configurable
 * weighted score, sends sequential job offers guarded by an exclusive Redis lock, expands the
 * search radius when no provider accepts, and drives the booking to PROVIDER_ACCEPTED or
 * SEARCHING_FAILED (Requirements 8.2-8.11, 19.5, 24.5). Emergency dispatch runs on a dedicated
 * bulkhead thread pool isolated from scheduled dispatch.
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        DispatchProperties.class,
        DispatchClientProperties.class,
        DispatchBulkheadProperties.class
})
// One base package, not one class per repository: ProcessedEventRepository sits in the
// com.homefix.shared.outbox.kafka SUBpackage, so naming both classes scans that subpackage
// twice and the duplicate bean definition fails startup.
@EntityScan(basePackages = {"com.homefix.dispatch", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.dispatch", "com.homefix.shared.outbox"})
public class DispatchEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchEngineApplication.class, args);
    }
}
