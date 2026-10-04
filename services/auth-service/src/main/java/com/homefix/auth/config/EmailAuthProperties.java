package com.homefix.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for email sign-up, email codes, password reset and staff invitations (email-auth spec,
 * Requirements 1, 3, 6 and 8).
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * homefix.auth.email-auth:
 *   code-ttl: 10m
 *   max-code-attempts: 5
 *   resend-cooldown: 60s
 *   max-codes-per-email-per-hour: 5
 *   max-requests-per-ip-per-hour: 10
 *   pending-signup-ttl: 24h
 *   invitation-ttl: 7d
 *   admin-portal-url: http://localhost:5175
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.auth.email-auth")
public class EmailAuthProperties {

    /** How long an emailed code stays valid (Requirement 1, 3.3). */
    private Duration codeTtl = Duration.ofMinutes(10);

    /** Wrong entries after which a code is spent (Requirement 3.3, Property EA2). */
    private int maxCodeAttempts = 5;

    /** Minimum gap between two codes sent to one address (Requirement 1.8). */
    private Duration resendCooldown = Duration.ofSeconds(60);

    /** Codes one address may be sent per hour, across sign-up, resend and reset (Requirement 1.8). */
    private int maxCodesPerEmailPerHour = 5;

    /** Sign-up, reset and invitation-acceptance requests one client IP may make per hour (Requirement 8.1). */
    private int maxRequestsPerIpPerHour = 10;

    /** Age after which an unverified sign-up stops blocking its email and mobile and is swept (Requirement 1.7). */
    private Duration pendingSignupTtl = Duration.ofHours(24);

    /** Lifetime of a staff invitation (Requirement 6.2). */
    private Duration invitationTtl = Duration.ofDays(7);

    /** Base URL of the Admin Portal, for the link in an invitation email. */
    private String adminPortalUrl = "http://localhost:5175";

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public int getMaxCodeAttempts() {
        return maxCodeAttempts;
    }

    public void setMaxCodeAttempts(int maxCodeAttempts) {
        this.maxCodeAttempts = maxCodeAttempts;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public int getMaxCodesPerEmailPerHour() {
        return maxCodesPerEmailPerHour;
    }

    public void setMaxCodesPerEmailPerHour(int maxCodesPerEmailPerHour) {
        this.maxCodesPerEmailPerHour = maxCodesPerEmailPerHour;
    }

    public int getMaxRequestsPerIpPerHour() {
        return maxRequestsPerIpPerHour;
    }

    public void setMaxRequestsPerIpPerHour(int maxRequestsPerIpPerHour) {
        this.maxRequestsPerIpPerHour = maxRequestsPerIpPerHour;
    }

    public Duration getPendingSignupTtl() {
        return pendingSignupTtl;
    }

    public void setPendingSignupTtl(Duration pendingSignupTtl) {
        this.pendingSignupTtl = pendingSignupTtl;
    }

    public Duration getInvitationTtl() {
        return invitationTtl;
    }

    public void setInvitationTtl(Duration invitationTtl) {
        this.invitationTtl = invitationTtl;
    }

    public String getAdminPortalUrl() {
        return adminPortalUrl;
    }

    public void setAdminPortalUrl(String adminPortalUrl) {
        this.adminPortalUrl = adminPortalUrl;
    }
}
