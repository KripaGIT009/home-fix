package com.homefix.shared.outbox;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot configuration so {@code @DataJpaTest} can discover the shared outbox
 * entities and repositories, which live in a library module with no application class of its own.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "com.homefix.shared.outbox")
@EnableJpaRepositories(basePackages = "com.homefix.shared.outbox")
public class TestJpaApplication {
}
