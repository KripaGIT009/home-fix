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
 *       introspection-cache:
 *         ttl: 30s
 *         max-entries: 10000
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

        /**
         * How long to wait for an introspection response before giving up.
         *
         * <p>Every authenticated request on the platform is introspected, so without a bound a
         * slow Auth Service does not merely delay one call: it holds gateway connections open
         * until they are exhausted and takes the whole platform down with it. A timeout turns
         * that into a fast 401 for the affected requests instead.
         */
        private java.time.Duration introspectTimeout = java.time.Duration.ofSeconds(3);

        /** Reuse of positive introspection results; see {@link IntrospectionCache}. */
        private final IntrospectionCache introspectionCache = new IntrospectionCache();

        public String getIntrospectUri() {
            return introspectUri;
        }

        public void setIntrospectUri(String introspectUri) {
            this.introspectUri = introspectUri;
        }

        public java.time.Duration getIntrospectTimeout() {
            return introspectTimeout;
        }

        public void setIntrospectTimeout(java.time.Duration introspectTimeout) {
            this.introspectTimeout = introspectTimeout;
        }

        public IntrospectionCache getIntrospectionCache() {
            return introspectionCache;
        }
    }

    /**
     * Short-lived reuse of <em>positive</em> introspection results
     * ({@code com.homefix.gateway.auth.CachingTokenIntrospector}).
     *
     * <p>An active result is reused for at most {@link #ttl}, and never past the token's own
     * expiry; inactive results and transport failures are never cached. The cost is revocation
     * latency: should the Auth Service start revoking access tokens before they expire, the gateway
     * honours that within {@code ttl}. Set {@code ttl} to {@code 0} to introspect every request.
     */
    public static class IntrospectionCache {
        /** Upper bound on how long an active result is reused; {@code 0} disables the cache. */
        private java.time.Duration ttl = java.time.Duration.ofSeconds(30);

        /** Upper bound on the number of distinct tokens held. */
        private long maxEntries = 10_000;

        public java.time.Duration getTtl() {
            return ttl;
        }

        public void setTtl(java.time.Duration ttl) {
            this.ttl = ttl;
        }

        public long getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(long maxEntries) {
            this.maxEntries = maxEntries;
        }

        /** The cache is active only with a positive ttl and a positive size bound. */
        public boolean isEnabled() {
            return ttl != null && !ttl.isNegative() && !ttl.isZero() && maxEntries > 0;
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
