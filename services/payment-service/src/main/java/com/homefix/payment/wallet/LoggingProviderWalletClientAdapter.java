package com.homefix.payment.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ProviderWalletClientPort} that logs the credit intent. A production adapter would
 * call the Provider Service wallet API (or emit a Kafka command) behind this same port without
 * touching payment logic.
 */
@Component
public class LoggingProviderWalletClientAdapter implements ProviderWalletClientPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderWalletClientAdapter.class);

    @Override
    public void creditEarning(UUID providerId, UUID bookingId, BigDecimal gross, BigDecimal platformFee,
                              BigDecimal netAmount) {
        log.info("WALLET_CREDIT provider={} booking={} gross={} platformFee={} net={}",
                providerId, bookingId, gross, platformFee, netAmount);
    }

    @Override
    public void creditSettlementReversal(UUID providerId, UUID settlementId, BigDecimal amount) {
        log.warn("WALLET_CREDIT_BACK provider={} settlement={} amount={} (failed settlement reversal)",
                providerId, settlementId, amount);
    }
}
