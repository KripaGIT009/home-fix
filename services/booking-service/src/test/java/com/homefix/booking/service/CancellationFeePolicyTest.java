package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.Bookings;

/**
 * Verifies the cancellation-fee rules (Requirement 9.16-9.18): no fee before
 * PROVIDER_ON_THE_WAY; a range-checked configured fee once the provider is on the way or later.
 */
class CancellationFeePolicyTest {

    private CancellationFeePolicy policyWithFee(BigDecimal configured, BigDecimal defaultFee) {
        BookingProperties props = new BookingProperties();
        props.setDefaultCancellationFee(defaultFee);
        SubcategoryCancellationFeePort port =
                subcategoryId -> Optional.ofNullable(configured);
        return new CancellationFeePolicy(port, props);
    }

    @Test
    void feeFreeStatesReturnZero() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("100.00"), new BigDecimal("0.00"));
        for (BookingStatus s : new BookingStatus[]{
                BookingStatus.CREATED, BookingStatus.SEARCHING_PROVIDER,
                // Both pre-acceptance Tenant states are fee-free (Requirement MT-9.3).
                BookingStatus.AWAITING_ASSIGNMENT,
                BookingStatus.PROVIDER_ASSIGNED, BookingStatus.PROVIDER_ACCEPTED}) {
            Booking b = Bookings.inState(s);
            assertThat(policy.feeFor(b))
                    .as("fee for %s", s)
                    .isEqualByComparingTo("0.00");
        }
    }

    @Test
    void feeChargedStatesReturnConfiguredSubcategoryFee() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("150.50"), new BigDecimal("0.00"));
        for (BookingStatus s : new BookingStatus[]{
                BookingStatus.PROVIDER_ON_THE_WAY, BookingStatus.PROVIDER_ARRIVED,
                BookingStatus.JOB_STARTED}) {
            Booking b = Bookings.inState(s);
            assertThat(policy.feeFor(b))
                    .as("fee for %s", s)
                    .isEqualByComparingTo("150.50");
        }
    }

    @Test
    void fallsBackToDefaultFeeWhenSubcategoryHasNone() {
        CancellationFeePolicy policy = policyWithFee(null, new BigDecimal("49.99"));
        Booking b = Bookings.inState(BookingStatus.PROVIDER_ON_THE_WAY);
        assertThat(policy.feeFor(b)).isEqualByComparingTo("49.99");
    }

    @Test
    void feeAboveMaximumIsRejected() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("1000.00"), new BigDecimal("0.00"));
        Booking b = Bookings.inState(BookingStatus.JOB_STARTED);
        assertThatThrownBy(() -> policy.feeFor(b))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("0.00-999.99");
    }

    @Test
    void negativeFeeIsRejected() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("-1.00"), new BigDecimal("0.00"));
        Booking b = Bookings.inState(BookingStatus.PROVIDER_ARRIVED);
        assertThatThrownBy(() -> policy.feeFor(b)).isInstanceOf(BookingException.class);
    }

    @Test
    void nonCancellableStateThrowsConflict() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("10.00"), new BigDecimal("0.00"));
        Booking b = Bookings.inState(BookingStatus.JOB_COMPLETED);
        assertThatThrownBy(() -> policy.feeFor(b))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getErrorCode())
                        .isEqualTo("CANCELLATION_NOT_ALLOWED"));
    }

    @Test
    void maxFeeBoundaryIsAccepted() {
        CancellationFeePolicy policy = policyWithFee(new BigDecimal("999.99"), new BigDecimal("0.00"));
        Booking b = Bookings.inState(BookingStatus.JOB_STARTED);
        assertThat(policy.feeFor(b)).isEqualByComparingTo("999.99");
    }
}
