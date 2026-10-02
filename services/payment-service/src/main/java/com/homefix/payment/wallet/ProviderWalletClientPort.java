package com.homefix.payment.wallet;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over the Provider Service wallet used to credit a provider's net earnings on a
 * successful payment (Requirement 12.10). Modelled as a port so the transport (REST to the
 * Provider Service, a Kafka command, etc.) can vary without touching payment logic, and so it is
 * mockable in unit tests.
 */
public interface ProviderWalletClientPort {

    /**
     * Credits {@code netAmount} to the provider's wallet for a completed booking.
     *
     * <p><strong>Must be idempotent per {@code bookingId}.</strong> A booking has exactly one
     * payment, and the Payment Service delivers this credit at least once: it re-sends a credit
     * whose confirmation it never durably recorded (a crash after the wallet accepted it, or a
     * failed marker clear), so an adapter must apply a repeated credit for the same booking only
     * once.
     *
     * @throws WalletCreditException if the credit could not be applied; the caller retries and,
     *                               on exhaustion, alerts Finance_Admin (Requirement 12.11).
     */
    void creditEarning(UUID providerId, UUID bookingId, BigDecimal gross, BigDecimal platformFee,
                       BigDecimal netAmount);

    /**
     * Credits a failed settlement amount back to the provider's wallet (Requirement 14.4).
     *
     * @throws WalletCreditException if the credit-back could not be applied.
     */
    void creditSettlementReversal(UUID providerId, UUID settlementId, BigDecimal amount);
}
