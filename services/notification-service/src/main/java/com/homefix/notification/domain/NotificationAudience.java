package com.homefix.notification.domain;

/**
 * The part a recipient plays in an event, which selects the message they are sent. The same event
 * can reach several people with different text: a cancellation tells the customer their booking is
 * cancelled and tells the assigned provider not to attend.
 */
public enum NotificationAudience {
    /** The customer who owns the booking or complaint. */
    CUSTOMER,
    /** The provider assigned to the booking. */
    PROVIDER,
    /** The author of a review (customer or provider). */
    REVIEWER,
    /** The subject of a review (customer or provider). */
    REVIEWEE
}
