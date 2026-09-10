package com.homefix.shared.outbox;

/**
 * Lifecycle states of a row in the transactional outbox table (Task 6).
 *
 * <ul>
 *   <li>{@link #PENDING} — written within the producing service's transaction, not yet
 *       relayed to Kafka.</li>
 *   <li>{@link #PUBLISHED} — successfully published to Kafka and acknowledged by the broker.</li>
 *   <li>{@link #FAILED} — the relay exhausted its retry budget and the row needs operator
 *       attention.</li>
 * </ul>
 */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
