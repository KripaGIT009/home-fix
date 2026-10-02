package com.homefix.notification;

import com.homefix.notification.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the HomeFix Notification Service.
 *
 * <p>Consumes the 11 booking-lifecycle Kafka events and the two complaint events idempotently,
 * decides who each one concerns, resolves those users' contact details from the Auth Service by
 * user id, and fans each out to the recipient's enabled channels (push, SMS, email, in-app) within
 * 10 s (Requirements 16.2, 16.3, 17). SMS and email delivery sit behind vendor-neutral ports so a
 * vendor swap needs only a new adapter. Delivery is deduplicated on
 * {@code (kafkaEventId, recipient, channel)} via a persisted delivery log and retried up to
 * three times with 1 s -> 2 s -> 4 s backoff before being marked permanently failed
 * (Property 22).
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.notification", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.notification", "com.homefix.shared.outbox"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
