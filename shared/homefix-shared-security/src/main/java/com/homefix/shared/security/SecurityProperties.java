package com.homefix.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level configuration for the shared security module.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * homefix:
 *   security:
 *     jwt-secret: ${JWT_SECRET}       # HMAC secret, >= 32 bytes for HS256
 *     enabled: true                   # master switch for the filter chain
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.security")
public class SecurityProperties {

    /** Master switch; when {@code false} no security filters are registered. */
    private boolean enabled = true;

    /** HMAC-SHA256 signing secret. Must be at least 256 bits (32 bytes). */
    private String jwtSecret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
