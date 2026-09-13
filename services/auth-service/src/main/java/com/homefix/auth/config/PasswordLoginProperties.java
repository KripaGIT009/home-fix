package com.homefix.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for username/password sign-in.
 *
 * <p>The defaults mirror the OTP flow's lockout (Requirement 1.3) so both authentication
 * surfaces resist brute force identically.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * homefix:
 *   auth:
 *     password:
 *       max-attempts: 5      # consecutive failures before lockout
 *       lockout: 30m         # how long a locked username stays locked
 *       failure-window: 15m  # window over which consecutive failures are counted
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.password")
public class PasswordLoginProperties {

    /** Consecutive failed attempts permitted before the username is locked. */
    private int maxAttempts = 5;

    /** How long a username stays locked after reaching {@link #maxAttempts}. */
    private Duration lockout = Duration.ofMinutes(30);

    /**
     * Window over which consecutive failures accumulate. A user who fails twice, waits out
     * the window and fails again starts from one rather than inching towards a lockout.
     */
    private Duration failureWindow = Duration.ofMinutes(15);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getLockout() {
        return lockout;
    }

    public void setLockout(Duration lockout) {
        this.lockout = lockout;
    }

    public Duration getFailureWindow() {
        return failureWindow;
    }

    public void setFailureWindow(Duration failureWindow) {
        this.failureWindow = failureWindow;
    }
}
