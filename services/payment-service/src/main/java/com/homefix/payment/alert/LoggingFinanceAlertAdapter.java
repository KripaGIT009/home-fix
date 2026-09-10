package com.homefix.payment.alert;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link FinanceAlertPort} that logs a structured CRITICAL alert. A production adapter can
 * publish to a Finance_Admin queue via the shared outbox without touching payment logic.
 */
@Component
public class LoggingFinanceAlertAdapter implements FinanceAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingFinanceAlertAdapter.class);

    @Override
    public void refundFailed(UUID paymentId, UUID bookingId, BigDecimal amount, String reason) {
        log.error("FINANCE_ALERT refund_failed payment={} booking={} amount={} reason={}",
                paymentId, bookingId, amount, reason);
    }

    @Override
    public void walletCreditFailed(UUID providerId, UUID bookingId, BigDecimal amount, String reason) {
        log.error("FINANCE_ALERT wallet_credit_failed provider={} booking={} amount={} reason={}",
                providerId, bookingId, amount, reason);
    }

    @Override
    public void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount, String reason) {
        log.error("FINANCE_ALERT settlement_failed provider={} settlement={} amount={} reason={}",
                providerId, settlementId, amount, reason);
    }
}
