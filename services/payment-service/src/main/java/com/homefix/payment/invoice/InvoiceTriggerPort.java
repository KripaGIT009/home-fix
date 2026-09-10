package com.homefix.payment.invoice;

import java.util.UUID;

/**
 * Abstraction over the Invoice Service trigger fired when a payment succeeds (Requirement 12.6).
 * Modelled as a port so the transport can vary and so it is mockable in unit tests.
 */
public interface InvoiceTriggerPort {

    /**
     * Requests invoice generation for a completed payment.
     *
     * @throws InvoiceTriggerException if the trigger fails; the caller retries with exponential
     *                                 backoff and logs a CRITICAL error on exhaustion.
     */
    void triggerInvoiceGeneration(UUID paymentId, UUID bookingId);
}
