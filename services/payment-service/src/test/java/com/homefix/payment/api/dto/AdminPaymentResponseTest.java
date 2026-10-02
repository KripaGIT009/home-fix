package com.homefix.payment.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.TransactionStatus;

/**
 * The domain-to-portal enum translation in {@link AdminPaymentResponse}: every domain value maps,
 * and only to a value the Admin Portal's {@code PaymentStatus} / {@code PaymentMethod} types know.
 */
class AdminPaymentResponseTest {

    /** The portal's {@code PaymentStatus} union (frontend/admin-portal/src/features/payments/api.ts). */
    private static final Set<String> PORTAL_STATUSES =
            Set.of("PENDING", "COMPLETED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED");

    /** The portal's {@code PaymentMethod} union. */
    private static final Set<String> PORTAL_METHODS = Set.of("CARD", "UPI", "NETBANKING", "WALLET", "CASH");

    @ParameterizedTest
    @CsvSource({
            "PENDING, PENDING",
            "SUCCESS, COMPLETED",
            "FAILED, FAILED",
            "REFUNDED, REFUNDED",
            "PARTIALLY_REFUNDED, PARTIALLY_REFUNDED"})
    void statusUsesThePortalNames(TransactionStatus domain, String portal) {
        assertThat(AdminPaymentResponse.status(domain)).isEqualTo(portal);
    }

    @ParameterizedTest
    @CsvSource({
            "UPI, UPI",
            "CREDIT_DEBIT_CARD, CARD",
            "NET_BANKING, NETBANKING",
            "WALLET, WALLET",
            "CASH, CASH"})
    void methodUsesThePortalNames(PaymentMethod domain, String portal) {
        assertThat(AdminPaymentResponse.method(domain)).isEqualTo(portal);
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus.class)
    void everyStatusMapsIntoThePortalVocabulary(TransactionStatus status) {
        assertThat(PORTAL_STATUSES).contains(AdminPaymentResponse.status(status));
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void everyMethodMapsIntoThePortalVocabulary(PaymentMethod method) {
        assertThat(PORTAL_METHODS).contains(AdminPaymentResponse.method(method));
    }
}
