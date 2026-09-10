package com.homefix.complaint;

import com.homefix.complaint.config.ComplaintProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the HomeFix Complaint Service (Requirement 16).
 *
 * <p>Creates complaint records against completed bookings and assigns them to an available
 * Support_Agent (16.1), acknowledges the complaint to the customer within 30 minutes (16.2),
 * notifies the customer of status changes via in-app notification within 5 minutes (16.3), enforces
 * resolution SLAs escalating to a Senior_Support_Agent on breach (16.4), coordinates refunds with
 * the Payment Service and sets REFUND_FAILED with a Finance_Admin alert on rejection (16.5, 16.6),
 * places/releases provider settlement holds on DISPUTED and closure (16.7, 16.8), and aggregates
 * complaint statistics for Admin reports (16.9).
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.homefix.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned. Scheduling is enabled to
 * drive the periodic SLA sweep (16.4) and stats refresh (16.9).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ComplaintProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.homefix.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.homefix.complaint", "com.homefix.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.homefix.complaint", "com.homefix.shared.outbox"})
public class ComplaintServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplaintServiceApplication.class, args);
    }
}
