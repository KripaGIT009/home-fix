package com.homefix.notification.contact;

/**
 * The contact directory could not be consulted. Treated as transient: it propagates out of the
 * consumer, so the shared consumer retries the whole event and, if the directory stays
 * unreachable, dead-letters it for replay. The message never contains contact details.
 */
public class ContactLookupException extends RuntimeException {

    public ContactLookupException(String message) {
        super(message);
    }

    public ContactLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
