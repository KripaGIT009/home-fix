package com.homefix.complaint.domain;

/**
 * Lifecycle of the single refund a complaint may carry (Requirement 16.5, 16.6).
 *
 * <pre>
 *   PENDING -&gt; SUCCEEDED | FAILED
 * </pre>
 *
 * <p>{@link #PENDING} is written and committed <em>before</em> the Payment Service is asked to move
 * money, so a refund that is in flight (or whose outcome was lost to a crash) is always visible and
 * blocks a second refund of the same complaint until Finance_Admin reconciles it.
 */
public enum ComplaintRefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
