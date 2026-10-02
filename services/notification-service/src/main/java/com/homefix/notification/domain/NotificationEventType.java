package com.homefix.notification.domain;

/**
 * The events the Notification Service reacts to: the 11 booking-lifecycle events of
 * Requirement 17.5, plus the two complaint events through which the customer is acknowledged
 * (Requirement 16.2) and told of status changes (Requirement 16.3).
 *
 * <p>Every event listed here must produce a notification; the {@link RecipientPolicy} decides who
 * receives it and the {@link EventTemplateResolver} maps each recipient to channels and content.
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
    REVIEW_SUBMITTED("ReviewSubmitted"),
    COMPLAINT_CREATED("ComplaintCreated"),
    COMPLAINT_STATUS_CHANGED("ComplaintStatusChanged");

    private final String eventName;

    NotificationEventType(String eventName) {
        this.eventName = eventName;
    }

    /** The wire/topic name of the event as published to Kafka. */
    public String eventName() {
        return eventName;
    }
}
