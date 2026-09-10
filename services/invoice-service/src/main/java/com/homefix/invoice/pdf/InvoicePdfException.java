package com.homefix.invoice.pdf;

/**
 * Signals a recoverable failure while rendering an invoice PDF. The orchestration layer retries
 * on this exception up to the configured limit before alerting operations (Requirement 13.7).
 */
public class InvoicePdfException extends RuntimeException {

    public InvoicePdfException(String message) {
        super(message);
    }

    public InvoicePdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
