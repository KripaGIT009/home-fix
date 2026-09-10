package com.homefix.notification.domain;

/**
 * Lifecycle status of a single (event, channel) delivery attempt recorded in the delivery log
 * (Requirement 17.7, 17.8).
 */
public enum DeliveryStatus {
    /** Delivered successfully to the channel vendor. */
    DELIVERED,
    /** All retry attempts were exhausted; the delivery will not be attempted again. */
    PERMANENTLY_FAILED,
    /** The channel was skipped because the user disabled it (Requirement 17.6). */
    SKIPPED_PREFERENCE
}
