package com.homefix.payment.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable payment-domain limits and thresholds (Requirement 12 and 14.3-14.4).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.payment")
public class PaymentProperties {

    /**
     * Maximum number of customer-driven retry attempts before a transaction is marked
     * permanently FAILED (Requirement 12.8).
     */
    private int maxCustomerRetries = 3;

    /**
     * Maximum number of retries for the PaymentCompleted publish / invoice trigger before a
     * CRITICAL error is logged for manual intervention (Requirement 12.6).
     */
    private int maxInvoiceRetries = 3;

    /**
     * Maximum number of retries for the provider wallet credit before the Finance_Admin team is
     * alerted for manual reconciliation (Requirement 12.11).
     */
    private int maxWalletCreditRetries = 3;

    /** Base backoff for exponential retry (doubled on each attempt). */
    private Duration retryBackoff = Duration.ofSeconds(1);

    /**
     * Default platform fee percentage applied when no explicit fee is supplied on the payment
     * request (Requirement 12.10). Expressed as a percentage, e.g. 20.00 == 20%.
     */
    private BigDecimal defaultPlatformFeePercent = new BigDecimal("20.00");

    /** TTL of the idempotency key entry in the store, covering the payment window (Requirement 12.3). */
    private Duration idempotencyTtl = Duration.ofHours(24);

    /** Idempotency store backend: 'redis' (production) or 'memory' (dev/test). */
    private String idempotencyStore = "redis";

    public int getMaxCustomerRetries() {
        return maxCustomerRetries;
    }

    public void setMaxCustomerRetries(int maxCustomerRetries) {
        this.maxCustomerRetries = maxCustomerRetries;
    }

    public int getMaxInvoiceRetries() {
        return maxInvoiceRetries;
    }

    public void setMaxInvoiceRetries(int maxInvoiceRetries) {
        this.maxInvoiceRetries = maxInvoiceRetries;
    }

    public int getMaxWalletCreditRetries() {
        return maxWalletCreditRetries;
    }

    public void setMaxWalletCreditRetries(int maxWalletCreditRetries) {
        this.maxWalletCreditRetries = maxWalletCreditRetries;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public BigDecimal getDefaultPlatformFeePercent() {
        return defaultPlatformFeePercent;
    }

    public void setDefaultPlatformFeePercent(BigDecimal defaultPlatformFeePercent) {
        this.defaultPlatformFeePercent = defaultPlatformFeePercent;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public String getIdempotencyStore() {
        return idempotencyStore;
    }

    public void setIdempotencyStore(String idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }
}
