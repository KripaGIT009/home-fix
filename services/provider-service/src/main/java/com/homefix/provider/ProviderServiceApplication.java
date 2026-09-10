package com.homefix.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.provider.config.ProviderProperties;

/**
 * Entry point for the HomeFix Provider Service.
 *
 * <p>Owns provider profile, skills, availability schedule, wallet balance, earnings history,
 * and settlement requests (Requirements 4 and 14). The shared security, observability, and
 * outbox libraries are wired automatically via their Spring Boot auto-configurations simply
 * by being on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderServiceApplication.class, args);
    }
}
