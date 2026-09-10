package com.homefix.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.homefix.admin.config.AdminProperties;

/**
 * Entry point for the HomeFix Admin Service.
 *
 * <p>Owns the Admin dashboard, the 15 operational modules, RBAC enforcement (ADMIN vs
 * SUPER_ADMIN), dispatch matching-weight validation, and the immutable Audit_Log
 * (Requirement 19). The shared security, observability, and outbox libraries are wired
 * automatically via their Spring Boot auto-configurations simply by being on the classpath
 * (Tasks 4-6).
 *
 * <p>{@code @EnableScheduling} drives the 60-second dashboard metric refresh (Requirement 19.1).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AdminProperties.class)
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }
}
