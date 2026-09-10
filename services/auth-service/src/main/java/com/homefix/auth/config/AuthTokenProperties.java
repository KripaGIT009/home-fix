package com.homefix.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for JWT access-token and refresh-token issuance.
 *
 * <p>The signing secret is shared with {@code homefix.security.jwt-secret} so that the
 * shared {@code JwtValidationFilter} (Task 4) can verify tokens issued by this service.
 *
 * <pre>
 * homefix:
 *   auth:
 *     token:
 *       issuer: homefix-auth
 *       access-ttl: 15m   # Requirement 1.8
 *       refresh-ttl: 30d  # Requirement 1.8
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.token")
public class AuthTokenProperties {

    /** {@code iss} claim placed on issued tokens. */
    private String issuer = "homefix-auth";

    /** Access-token validity. Maximum 15 minutes (Requirement 1.8). */
    private Duration accessTtl = Duration.ofMinutes(15);

    /** Refresh-token validity. Maximum 30 days (Requirement 1.8). */
    private Duration refreshTtl = Duration.ofDays(30);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }
}
