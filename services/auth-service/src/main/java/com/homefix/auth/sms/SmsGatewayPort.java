package com.homefix.auth.sms;

/**
 * Hexagonal port abstracting the outbound SMS gateway.
 *
 * <p>The OTP registration flow depends only on this interface, never on a concrete
 * vendor SDK. Adding a new provider (Twilio, MSG91, SNS, ...) requires only a new
 * adapter implementing this port — no change to the OTP business logic. This mirrors
 * the {@code PaymentGatewayPort} / {@code SmsPort} abstraction pattern established in
 * the platform design.
 */
public interface SmsGatewayPort {

    /**
     * Sends an SMS message to the supplied mobile number.
     *
     * @param mobileNumber E.164 mobile number (e.g. {@code +919876543210})
     * @param message      the message body to deliver
     * @throws SmsDeliveryException if the number is undeliverable/invalid or the gateway
     *                              fails to accept the message (Requirement 1.16)
     */
    void send(String mobileNumber, String message) throws SmsDeliveryException;
}
