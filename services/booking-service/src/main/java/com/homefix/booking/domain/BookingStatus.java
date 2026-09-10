package com.homefix.booking.domain;

/**
 * The complete set of Booking lifecycle states (Requirement 9.1). Persisted as a string in
 * the {@code booking.status} column and used as the key/value type of the
 * {@link BookingStateMachine} permitted-transition map.
 */
public enum BookingStatus {
    CREATED,
    SEARCHING_PROVIDER,
    SEARCHING_FAILED,
    PROVIDER_ASSIGNED,
    PROVIDER_ACCEPTED,
    PROVIDER_ON_THE_WAY,
    PROVIDER_ARRIVED,
    JOB_STARTED,
    JOB_PAUSED,
    ADDITIONAL_QUOTE_REQUIRED,
    CUSTOMER_APPROVAL_PENDING,
    JOB_COMPLETED,
    CUSTOMER_CONFIRMED,
    PAYMENT_PENDING,
    PAYMENT_COMPLETED,
    DISPUTED,
    REFUNDED,
    CANCELLED
}
