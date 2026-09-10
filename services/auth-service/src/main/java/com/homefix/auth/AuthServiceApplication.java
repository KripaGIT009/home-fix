package com.homefix.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.config.OtpProperties;
import com.homefix.auth.config.SocialLoginProperties;

/**
 * Entry point for the HomeFix Auth Service.
 *
 * <p>Registration and JWT issuance service. The shared security, observability, and
 * outbox libraries are wired automatically via their Spring Boot auto-configurations
 * simply by being on the classpath (Tasks 4-6).
 */
@SpringBootApplication
@EnableConfigurationProperties({OtpProperties.class, AuthTokenProperties.class, SocialLoginProperties.class})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
