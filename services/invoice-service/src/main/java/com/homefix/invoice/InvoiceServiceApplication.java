package com.homefix.invoice;

import com.homefix.invoice.config.InvoiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the HomeFix Invoice Service.
 *
 * <p>Consumes {@code PaymentCompleted} idempotently, renders a PDF invoice, stores it in S3 with
 * server-side encryption, assigns a globally-unique per-month invoice number
 * ({@code INV-YYYY-MM-NNNNNN}), and delivers a 72-hour signed URL to the customer via the
 * Notification Service within 60 s (Requirement 13, Property 14). PDF generation retries up to
 * three times and alerts the operations team on exhaustion without blocking the payment flow.
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(InvoiceProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.invoice", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.invoice", "com.homefix.shared.outbox"})
public class InvoiceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceServiceApplication.class, args);
    }
}
