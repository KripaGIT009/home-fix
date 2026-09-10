package com.homefix.payment.invoice;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link InvoiceTriggerPort} that logs the trigger intent. A production adapter would call
 * the Invoice Service (or rely on the PaymentCompleted Kafka event the Invoice Service consumes)
 * behind this same port without touching payment logic.
 */
@Component
public class LoggingInvoiceTriggerAdapter implements InvoiceTriggerPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingInvoiceTriggerAdapter.class);

    @Override
    public void triggerInvoiceGeneration(UUID paymentId, UUID bookingId) {
        log.info("INVOICE_TRIGGER payment={} booking={}", paymentId, bookingId);
    }
}
