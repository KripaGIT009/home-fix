package com.homefix.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.homefix.location.config.LocationProperties;

/**
 * Entry point for the HomeFix Location Service.
 *
 * <p>Ingests Provider GPS updates (max one per 5 s per Provider per active Booking), caches the
 * last known location in Redis, persists the full history to PostgreSQL for dispute resolution,
 * calculates ETA to the Customer address, and pushes coordinates + ETA to subscribed Customers
 * over WebSocket/SSE. On a {@code JobStarted} Kafka event the service terminates all active
 * subscriptions and stops accepting updates for that Booking (Requirement 10).
 *
 * <p>{@link EntityScan} and {@link EnableJpaRepositories} include the shared outbox package so
 * the processed-event dedup table used by {@code IdempotentKafkaConsumer} is registered
 * alongside the location aggregate.
 */
@SpringBootApplication
@EnableConfigurationProperties(LocationProperties.class)
@EntityScan(basePackages = {"com.homefix.location.domain", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.location.domain", "com.homefix.shared.outbox"})
public class LocationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }
}
