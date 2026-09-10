package com.homefix.complaint.alert;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link FinanceAlertPort} that logs a structured internal alert (Requirement 16.6). A
 * production adapter can publish to Kafka via the shared outbox without touching the complaint
 * logic. Activated only when no other {@link FinanceAlertPort} bean is present (tests supply their
 * own).
 */
@Component
public class LoggingFinanceAlertAdapter implements FinanceAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingFinanceAlertAdapter.class);

    @Override
    public void refundRequiresManualProcessing(UUID complaintId, UUID bookingId, BigDecimal amount,
                                                String reason) {
        log.warn("FINANCE_ALERT complaint={} refund rejected by Payment Service; manual processing "
                + "required (reason recorded)", complaintId);
    }
}
