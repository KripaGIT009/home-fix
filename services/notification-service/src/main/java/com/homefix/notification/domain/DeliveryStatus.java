package com.homefix.notification.domain;

/**
 * Lifecycle status of a single (event, recipient, channel) delivery attempt recorded in the delivery log
 * (Requirement 17.7, 17.8).
 */
public enum DeliveryStatus {
    /** Delivered successfully to the channel vendor. */
    DELIVERED,
    /** All retry attempts were exhausted; the delivery will not be attempted again. */
    PERMANENTLY_FAILED,
    /** The channel was skipped because the user disabled it (Requirement 17.6). */
    SKIPPED_PREFERENCE,
    /**
     * The channel was skipped because the platform holds no address for the recipient on it (no
     * phone for SMS, no email, no push token). Retrying cannot succeed, so none is attempted.
     */
    SKIPPED_NO_CONTACT
}
