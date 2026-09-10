package com.homefix.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the gateway's application-level security controls (Requirement 23).
 *
 * <pre>
 * homefix:
 *   gateway:
 *     https-enforced: true
 *     auth:
 *       introspect-uri: http://auth-service:8081/auth/introspect
 *     rate-limit:
 *       customer-per-minute: 100
 *       provider-per-minute: 60
 *       default-per-minute: 60
 *     otp:
 *       max-per-phone-per-hour: 5
 *       otp-paths: /auth/register/otp
 * </pre>
 */
@ConfigurationProperties(prefix = "homefix.gateway")
public class GatewaySecurityProperties {

    /** Master switch for HTTP->HTTPS 301 redirect + TLS enforcement (Requirement 23.7, 23.8). */
    private boolean httpsEnforced = true;

    private final Auth auth = new Auth();
    private final RateLimit rateLimit = new RateLimit();
    private final Otp otp = new Otp();

    public boolean isHttpsEnforced() {
        return httpsEnforced;
    }

    public void setHttpsEnforced(boolean httpsEnforced) {
        this.httpsEnforced = httpsEnforced;
    }

    public Auth getAuth() {
        return auth;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Otp getOtp() {
        return otp;
    }

    /** Auth Service introspection settings (Requirement 23.1). */
    public static class Auth {
        /** Absolute URI of the Auth Service {@code GET /auth/introspect} endpoint. */
        private String introspectUri = "http://localhost:8081/auth/introspect";

        public String getIntrospectUri() {
            return introspectUri;
        }

        public void setIntrospectUri(String introspectUri) {
            this.introspectUri = introspectUri;
        }
    }

    /** Per-user request rate-limit settings (Requirement 23.3, 23.5). */
    public static class RateLimit {
        private int customerPerMinute = 100;
        private int providerPerMinute = 60;
        private int defaultPerMinute = 60;

        public int getCustomerPerMinute() {
            return customerPerMinute;
        }

        public void setCustomerPerMinute(int customerPerMinute) {
            this.customerPerMinute = customerPerMinute;
        }

        public int getProviderPerMinute() {
            return providerPerMinute;
        }

        public void setProviderPerMinute(int providerPerMinute) {
            this.providerPerMinute = providerPerMinute;
        }

        public int getDefaultPerMinute() {
            return defaultPerMinute;
        }

        public void setDefaultPerMinute(int defaultPerMinute) {
            this.defaultPerMinute = defaultPerMinute;
        }
    }

    /** Per-phone OTP rate-limit settings (Requirement 23.4). */
    public static class Otp {
        private int maxPerPhonePerHour = 5;
        /** Request path prefix that triggers OTP-specific throttling. */
        private String otpPath = "/auth/register/otp";

        public int getMaxPerPhonePerHour() {
            return maxPerPhonePerHour;
        }

        public void setMaxPerPhonePerHour(int maxPerPhonePerHour) {
            this.maxPerPhonePerHour = maxPerPhonePerHour;
        }

        public String getOtpPath() {
            return otpPath;
        }

        public void setOtpPath(String otpPath) {
            this.otpPath = otpPath;
        }
    }
}
