package com.homefix.payment.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderWalletClientPort} that only logs the credit intent, for tests and for running the
 * Payment Service without a Provider Service. Selected with
 * {@code homefix.payment.provider-wallet.client=logging} (the default); deployments use
 * {@link HttpProviderWalletClientAdapter}. Under this adapter no provider is ever paid.
 */
@Component
@ConditionalOnProperty(name = "homefix.payment.provider-wallet.client", havingValue = "logging",
        matchIfMissing = true)
public class LoggingProviderWalletClientAdapter implements ProviderWalletClientPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderWalletClientAdapter.class);

    @Override
    public void creditEarning(UUID providerId, UUID bookingId, String bookingReference, BigDecimal gross,
                              BigDecimal platformFee, BigDecimal netAmount) {
        log.info("WALLET_CREDIT provider={} booking={} reference={} gross={} platformFee={} net={}",
                providerId, bookingId, bookingReference, gross, platformFee, netAmount);
    }

    @Override
    public void creditSettlementReversal(UUID providerId, UUID settlementId, BigDecimal amount) {
        log.warn("WALLET_CREDIT_BACK provider={} settlement={} amount={} (failed settlement reversal)",
                providerId, settlementId, amount);
    }
}
