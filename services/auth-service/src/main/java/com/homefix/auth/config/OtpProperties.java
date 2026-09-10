package com.homefix.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OTP registration flow.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * homefix:
 *   auth:
 *     otp:
 *       ttl: 5m                 # OTP validity window (Requirement 1.2, 1.4)
 *       length: 6               # number of digits
 *       max-verify-attempts: 5  # consecutive wrong attempts before lockout (Requirement 1.3)
 *       lockout: 30m            # lockout window after max attempts (Requirement 1.3, Property 25)
 *       max-requests-per-hour: 5 # per-phone OTP request cap (Requirement 23.4)
 *       rate-limit-window: 1h
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.otp")
public class OtpProperties {

    /** OTP validity window. Default 5 minutes (Requirement 1.2, 1.4). */
    private Duration ttl = Duration.ofMinutes(5);

    /** Number of numeric digits in a generated OTP. */
    private int length = 6;

    /** Consecutive incorrect submissions permitted before lockout (Requirement 1.3). */
    private int maxVerifyAttempts = 5;

    /** Duration a session is locked after reaching {@link #maxVerifyAttempts} (Property 25). */
    private Duration lockout = Duration.ofMinutes(30);

    /** Maximum OTP requests allowed per phone within {@link #rateLimitWindow} (Requirement 23.4). */
    private int maxRequestsPerWindow = 5;

    /** Rate-limit sliding window for OTP requests. Default 1 hour (Requirement 23.4). */
    private Duration rateLimitWindow = Duration.ofHours(1);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getMaxVerifyAttempts() {
        return maxVerifyAttempts;
    }

    public void setMaxVerifyAttempts(int maxVerifyAttempts) {
        this.maxVerifyAttempts = maxVerifyAttempts;
    }

    public Duration getLockout() {
        return lockout;
    }

    public void setLockout(Duration lockout) {
        this.lockout = lockout;
    }

    public int getMaxRequestsPerWindow() {
        return maxRequestsPerWindow;
    }

    public void setMaxRequestsPerWindow(int maxRequestsPerWindow) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }
}
