package com.homefix.outbox;

import com.homefix.outbox.config.OutboxProcessorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the HomeFix Outbox Processor.
 *
 * <p>A standalone relay that drains the shared transactional-outbox table: it claims due
 * {@link com.homefix.shared.outbox.OutboxEventStatus#PENDING} rows in configurable batches
 * ({@code SELECT ... FOR UPDATE SKIP LOCKED} plus a claim lease, so several instances can run
 * side by side), publishes each event to its Kafka topic via the shared idempotent producer, and
 * marks the row {@link com.homefix.shared.outbox.OutboxEventStatus#PUBLISHED} once the broker
 * acknowledges. A failed publish is rescheduled on the row with exponential backoff (1 s doubling
 * up to a 60 s cap, default 10 attempts) rather than slept on; on exhaustion the relay emits an
 * alert with the event ID, Kafka topic, and total attempt count and marks the row
 * {@link com.homefix.shared.outbox.OutboxEventStatus#FAILED} (Requirement 22.4). Delivery is
 * at-least-once; consumers deduplicate on the {@code eventId} header.
 *
 * <p>The shared observability library auto-configures structured JSON logging from the
 * classpath. The shared outbox JPA entity and repository live in
 * {@code com.homefix.shared.outbox}, so they are explicitly scanned alongside this
 * application's own packages. {@code @EnableScheduling} drives the periodic poll.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OutboxProcessorProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.outbox", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.outbox", "com.homefix.shared.outbox"})
public class OutboxProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxProcessorApplication.class, args);
    }
}
