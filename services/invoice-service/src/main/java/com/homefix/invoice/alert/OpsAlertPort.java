package com.homefix.invoice.alert;

import java.util.UUID;

/**
 * Outbound port for alerting the operations team when invoice PDF generation fails after all
 * retries are exhausted (Requirement 13.7). Hidden behind a port so the exhaustion path is
 * unit-testable without a real alerting channel.
 */
public interface OpsAlertPort {

    /**
     * Raises an operations alert for an invoice that could not be generated.
     *
     * @param bookingId the booking whose invoice failed
     * @param paymentId the payment that triggered generation
     * @param reason    a short human-readable failure reason
     */
    void alertInvoiceGenerationFailed(UUID bookingId, UUID paymentId, String reason);
}
