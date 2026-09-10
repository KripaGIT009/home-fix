package com.homefix.verification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.verification.config.VerificationProperties;

/**
 * Entry point for the HomeFix Verification Service.
 *
 * <p>Owns the Provider verification state machine, document upload with server-side
 * encryption, background-check integration, the Admin approval workflow, and a complete
 * audit trail of every status change (Requirement 5). The shared security, observability,
 * and outbox libraries are wired automatically via their Spring Boot auto-configurations
 * simply by being on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerificationServiceApplication.class, args);
    }
}
