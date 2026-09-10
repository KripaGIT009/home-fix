package com.homefix.auth.sms;

/**
 * Thrown by an {@link SmsGatewayPort} implementation when a message cannot be delivered,
 * for example an undeliverable or invalid mobile number (Requirement 1.16).
 *
 * <p>When this is raised during OTP send, the Auth Service must NOT create a pending OTP
 * session and must surface a delivery-failure error to the caller.
 */
public class SmsDeliveryException extends RuntimeException {

    public SmsDeliveryException(String message) {
        super(message);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
