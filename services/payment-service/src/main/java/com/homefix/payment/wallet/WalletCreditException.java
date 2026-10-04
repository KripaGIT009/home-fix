package com.homefix.payment.wallet;

/**
 * Raised when a provider wallet credit (or settlement credit-back) fails, so the Payment Service
 * can apply its retry-then-alert policy (Requirement 12.11, 14.4).
 *
 * <p>A <em>permanent</em> failure is one the wallet answered with a definite refusal that no re-send
 * can change (an unknown provider, an invalid credit, a booking already credited to another
 * provider). The Payment Service stops re-sending such a credit and alerts Finance_Admin instead of
 * retrying it on every sweep forever. Anything else (unreachable, 5xx, a misconfigured service
 * credential that will be fixed) is transient and keeps being re-sent.
 */
public class WalletCreditException extends RuntimeException {

    private final boolean permanent;

    public WalletCreditException(String message) {
        this(message, null, false);
    }

    public WalletCreditException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public WalletCreditException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    /** @return whether re-sending the same credit can never succeed. */
    public boolean isPermanent() {
        return permanent;
    }
}
