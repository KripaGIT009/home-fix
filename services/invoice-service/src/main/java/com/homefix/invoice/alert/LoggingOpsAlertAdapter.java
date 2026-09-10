package com.homefix.invoice.alert;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link OpsAlertPort} adapter that logs a CRITICAL entry for the operations team when
 * invoice PDF generation is exhausted (Requirement 13.7). In production this can be replaced by a
 * PagerDuty/SNS adapter behind the same port without touching orchestration.
 *
 * <p>Active only when no other {@link OpsAlertPort} bean is present (tests supply a fake).
 */
@Component
public class LoggingOpsAlertAdapter implements OpsAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingOpsAlertAdapter.class);

    @Override
    public void alertInvoiceGenerationFailed(UUID bookingId, UUID paymentId, String reason) {
        log.error("CRITICAL invoice PDF generation failed after retries; booking={} payment={} reason={}",
                bookingId, paymentId, reason);
    }
}
