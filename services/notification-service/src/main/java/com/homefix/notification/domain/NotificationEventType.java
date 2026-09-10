package com.homefix.notification.domain;

/**
 * The 11 booking-lifecycle events the Notification Service reacts to (Requirement 17.5).
 *
 * <p>Every event listed here must produce a notification; the {@link EventTemplateResolver} maps
 * each to its default channels and message content.
 */
public enum NotificationEventType {
    BOOKING_CREATED("BookingCreated"),
    PROVIDER_ASSIGNED("ProviderAssigned"),
    PROVIDER_ACCEPTED("ProviderAccepted"),
    PROVIDER_REJECTED("ProviderRejected"),
    PROVIDER_ARRIVING("ProviderArriving"),
    PROVIDER_ARRIVED("ProviderArrived"),
    JOB_STARTED("JobStarted"),
    JOB_COMPLETED("JobCompleted"),
    PAYMENT_COMPLETED("PaymentCompleted"),
    BOOKING_CANCELLED("BookingCancelled"),
    REVIEW_SUBMITTED("ReviewSubmitted");

    private final String eventName;

    NotificationEventType(String eventName) {
        this.eventName = eventName;
    }

    /** The wire/topic name of the event as published to Kafka. */
    public String eventName() {
        return eventName;
    }
}
