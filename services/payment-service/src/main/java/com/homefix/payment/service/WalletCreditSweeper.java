package com.homefix.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-sends provider wallet credits that are still owed after a payment succeeded
 * (Requirement 12.10, 12.11). The credit runs after the SUCCESS commit, so a crash in between, or
 * an in-line retry run that ran out, would otherwise lose the provider's earnings; the SUCCESS
 * commit records the debt durably and this sweep settles it. The work lives in
 * {@link PaymentService#retryPendingWalletCredits()} so it can be unit-tested without a scheduler.
 */
@Component
public class WalletCreditSweeper {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditSweeper.class);

    private final PaymentService paymentService;

    public WalletCreditSweeper(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Runs on a fixed delay; a failed sweep is retried on the next tick. */
    @Scheduled(fixedDelayString = "${homefix.payment.wallet-credit-sweep-interval:PT1M}")
    public void sweep() {
        try {
            int credited = paymentService.retryPendingWalletCredits();
            if (credited > 0) {
                log.info("Wallet credit sweep delivered {} owed credit(s)", credited);
            }
        } catch (RuntimeException e) {
            log.warn("Wallet credit sweep failed; will retry on next tick: {}", e.getMessage());
        }
    }
}
