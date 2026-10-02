package com.homefix.pricing.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Promotion Service, which quotes coupon discounts (Requirement 6.10).
 *
 * <p>The timeouts are deliberately short. The Booking Service calls the estimate endpoint with a
 * 5-second per-attempt budget and retries a 5xx itself, so a slow Promotion Service must surface
 * here as a prompt {@code 503} rather than consume that budget; the worst case is
 * {@code connectTimeout + readTimeout}.
 */
@ConfigurationProperties(prefix = "homefix.pricing.promotion")
public class PromotionClientProperties {

    /** Base URL of the Promotion Service on the internal network. */
    private String baseUrl = "http://promotion-service:8098";

    /**
     * Shared service credential presented as {@code X-Internal-Api-Key} on
     * {@code /internal/**}. No default: when blank, coupon quotes fail with a 503.
     */
    private String internalApiKey;

    /** Maximum time to establish the TCP connection. */
    private Duration connectTimeout = Duration.ofSeconds(1);

    /** Maximum time to wait for the response once connected. */
    private Duration readTimeout = Duration.ofSeconds(2);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
