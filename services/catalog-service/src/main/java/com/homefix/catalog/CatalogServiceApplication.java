package com.homefix.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.catalog.config.CatalogProperties;

/**
 * Entry point for the HomeFix Service Catalog Service.
 *
 * <p>Owns the database-driven list of service categories and subcategories with Admin CRUD and
 * a Redis read-through cache (Requirement 3). The shared security, observability, and outbox
 * libraries are wired automatically via their Spring Boot auto-configurations simply by being
 * on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
