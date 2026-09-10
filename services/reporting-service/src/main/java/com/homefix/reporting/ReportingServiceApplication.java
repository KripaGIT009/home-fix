package com.homefix.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.reporting.config.ReportingProperties;

/**
 * Entry point for the HomeFix Reporting Service.
 *
 * <p>Owns the six pre-built reports, the synchronous (&le; 7-day) and asynchronous (&gt; 7-day)
 * generation paths, PDF/CSV export, the 7-day expiring download link, and the Finance_Admin
 * restriction on Payment Reconciliation and Settlement reports (Requirement 20). The analytics
 * data store, email delivery, and download-link storage are modelled as ports with stub/logging
 * adapters so the service is independently buildable.
 *
 * <p>The shared security, observability, and outbox libraries are wired automatically via their
 * Spring Boot auto-configurations simply by being on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties(ReportingProperties.class)
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
