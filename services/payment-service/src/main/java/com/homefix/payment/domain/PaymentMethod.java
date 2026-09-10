package com.homefix.payment.domain;

/**
 * Supported payment methods (Requirement 12.2).
 */
public enum PaymentMethod {
    UPI,
    CREDIT_DEBIT_CARD,
    NET_BANKING,
    WALLET,
    CASH
}
