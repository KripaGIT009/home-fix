package com.homefix.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.homefix.payment.config.PaymentProperties;

/**
 * Entry point for the HomeFix Payment Service.
 *
 * <p>Owns multi-gateway payment processing (Requirement 12): a single {@code PaymentGatewayPort}
 * abstraction with Razorpay/Stripe adapters, idempotency scoped to {@code (customerId, bookingId)},
 * a strict transaction state machine, cryptographic callback signature verification, PaymentCompleted
 * event publishing via the shared outbox, invoice triggering, provider wallet credit, refunds, and
 * settlement bank transfers (Requirement 14.3-14.4).
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence package are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(PaymentProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.payment", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.payment", "com.homefix.shared.outbox"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
