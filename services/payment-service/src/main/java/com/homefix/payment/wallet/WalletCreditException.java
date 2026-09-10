package com.homefix.payment.wallet;

/**
 * Raised when a provider wallet credit (or settlement credit-back) fails, so the Payment Service
 * can apply its retry-then-alert policy (Requirement 12.11, 14.4).
 */
public class WalletCreditException extends RuntimeException {

    public WalletCreditException(String message) {
        super(message);
    }

    public WalletCreditException(String message, Throwable cause) {
        super(message, cause);
    }
}
