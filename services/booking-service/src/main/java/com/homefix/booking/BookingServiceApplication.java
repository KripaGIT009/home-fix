package com.homefix.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.homefix.booking.config.BookingProperties;

/**
 * Entry point for the HomeFix Booking Service.
 *
 * <p>Handles booking creation (scheduled and emergency), itemized price estimates via the
 * Pricing Engine, booking lifecycle state-machine enforcement with a contiguous audit trail,
 * media upload to S3, cancellation-fee logic, and Saga orchestration with compensating
 * transactions (Requirements 7, 8.1, 9, 24.6-24.7). The shared security, observability, and
 * outbox libraries are wired via their Spring Boot auto-configurations by being on the
 * classpath (Tasks 4-6).
 *
 * <p>{@link EntityScan} and {@link EnableJpaRepositories} include the shared outbox package so
 * the transactional-outbox entity and repository are registered alongside the booking
 * aggregate, letting {@code OutboxEventPublisher} write within the booking transaction.
 */
@SpringBootApplication
@EnableConfigurationProperties(BookingProperties.class)
@EntityScan(basePackages = {"com.homefix.booking.domain", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.booking.domain", "com.homefix.shared.outbox"})
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
