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

    /**
     * Oldest signed gateway-callback {@code timestamp} still accepted (Requirement 12.5). Bounds how
     * long a captured payload stays usable while leaving room for a gateway's own delivery retries,
     * which can span days. {@code 0} disables the age check; a timestamp more than five minutes in
     * the future is always rejected.
     */
    private Duration callbackMaxAge = Duration.ofHours(72);

    /**
     * How long a provider wallet credit must have been owed before the wallet-credit sweeper re-sends
     * it (Requirement 12.10, 12.11). Keeps the sweep clear of a credit the callback thread is still
     * retrying, so it must comfortably exceed the in-line retry window
     * ({@code max-wallet-credit-retries} attempts with doubling {@code retry-backoff}).
     */
    private Duration walletCreditSweepMinAge = Duration.ofMinutes(5);

    /**
     * Gateway a booking payment is sent to when the client names none ({@code POST /payments}
     * without {@code gatewayId}). Production keeps {@code razorpay}; the local compose stack sets
     * {@code simulator}, which exists only when {@code gateways.simulator.enabled=true}.
     */
    private String defaultGateway = "razorpay";

    /**
     * Payment attempts one customer may open for one booking (Requirement 12.3, 12.8). A new attempt
     * is opened only after every earlier one FAILED; the cap stops a client from cycling cards
     * against one booking without limit (card testing) and bounds the attempt lookup.
     */
    private int maxPaymentAttempts = 10;

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

    public Duration getCallbackMaxAge() {
        return callbackMaxAge;
    }

    public void setCallbackMaxAge(Duration callbackMaxAge) {
        this.callbackMaxAge = callbackMaxAge;
    }

    public Duration getWalletCreditSweepMinAge() {
        return walletCreditSweepMinAge;
    }

    public void setWalletCreditSweepMinAge(Duration walletCreditSweepMinAge) {
        this.walletCreditSweepMinAge = walletCreditSweepMinAge;
    }

    public String getDefaultGateway() {
        return defaultGateway;
    }

    public void setDefaultGateway(String defaultGateway) {
        this.defaultGateway = defaultGateway;
    }

    public int getMaxPaymentAttempts() {
        return maxPaymentAttempts;
    }

    public void setMaxPaymentAttempts(int maxPaymentAttempts) {
        this.maxPaymentAttempts = maxPaymentAttempts;
    }
}
