package com.homefix.payment.invoice;

/**
 * Raised when triggering invoice generation fails, so the Payment Service can apply its
 * retry-then-log-CRITICAL policy (Requirement 12.6).
 */
public class InvoiceTriggerException extends RuntimeException {

    public InvoiceTriggerException(String message) {
        super(message);
    }

    public InvoiceTriggerException(String message, Throwable cause) {
        super(message, cause);
    }
}
