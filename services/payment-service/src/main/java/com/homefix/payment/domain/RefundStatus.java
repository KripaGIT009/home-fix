package com.homefix.payment.domain;

/**
 * Lifecycle of a single {@link PaymentRefund} attempt (Requirement 12.7).
 *
 * <pre>
 *   PENDING -&gt; SUCCEEDED | FAILED
 * </pre>
 *
 * <p>{@code PENDING} is written and committed <em>before</em> the gateway is asked to move money,
 * so a refund that is in flight (or whose outcome was lost to a crash) is always visible and blocks
 * a second refund of the same transaction until it is resolved.
 */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
