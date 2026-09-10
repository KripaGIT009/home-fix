package com.homefix.notification.domain;

/**
 * Per-channel contact details for a notification recipient.
 *
 * <p>These are PII (phone/email) and must never be logged (Requirement 26.4). Any field may be
 * {@code null} when the corresponding channel address is unknown; the orchestrator skips a
 * channel whose address is missing.
 *
 * @param mobileNumber E.164 mobile number for the SMS channel
 * @param emailAddress email address for the email channel
 * @param deviceToken  device/registration token for the push channel
 */
public record NotificationContact(String mobileNumber, String emailAddress, String deviceToken) {

    private static final NotificationContact EMPTY = new NotificationContact(null, null, null);

    public static NotificationContact empty() {
        return EMPTY;
    }
}
