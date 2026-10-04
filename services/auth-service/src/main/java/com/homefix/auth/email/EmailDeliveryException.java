package com.homefix.auth.email;

/** An email could not be handed over for delivery. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
