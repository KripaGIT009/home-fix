package com.homefix.chat;

import com.homefix.chat.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the HomeFix Chat Service (Requirement 18).
 *
 * <p>Manages the in-app chat channel that links the Customer and Provider on a booking. Channels
 * are activated when a booking reaches PROVIDER_ACCEPTED and deactivated on PAYMENT_COMPLETED or
 * CANCELLED, driven by idempotent Kafka consumption. Messages are delivered over WebSocket and,
 * for offline recipients, a push notification is raised via the Notification Service. Access is
 * restricted to the two booking participants (Property 23) and personal phone numbers are masked
 * before a message is stored or delivered. Messages are retained for at least 90 days from the
 * booking's creation date regardless of channel status.
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(ChatProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.chat", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.chat", "com.homefix.shared.outbox"})
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
