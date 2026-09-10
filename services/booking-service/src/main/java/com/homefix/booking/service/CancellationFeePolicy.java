package com.homefix.booking.service;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;

/**
 * Encodes the cancellation-fee rules (Requirement 9.16-9.18):
 * <ul>
 *   <li>No fee when cancelling in CREATED, SEARCHING_PROVIDER, PROVIDER_ASSIGNED, or
 *       PROVIDER_ACCEPTED (Requirement 9.16).</li>
 *   <li>A configured fee once the provider is on the way or later — PROVIDER_ON_THE_WAY,
 *       PROVIDER_ARRIVED, JOB_STARTED (Requirement 9.17-9.18).</li>
 * </ul>
 *
 * <p>The fee is the Service_Subcategory-configured amount (looked up via
 * {@link SubcategoryCancellationFeePort}) and MUST be within the 0.00-999.99 range
 * (Requirement 9.18); values outside that range are rejected.
 */
@Component
public class CancellationFeePolicy {

    /** States in which cancellation incurs no fee (Requirement 9.16). */
    private static final Set<BookingStatus> FEE_FREE = EnumSet.of(
            BookingStatus.CREATED,
            BookingStatus.SEARCHING_PROVIDER,
            BookingStatus.PROVIDER_ASSIGNED,
            BookingStatus.PROVIDER_ACCEPTED);

    /** States in which the configured cancellation fee applies (Requirement 9.17-9.18). */
    private static final Set<BookingStatus> FEE_CHARGED = EnumSet.of(
            BookingStatus.PROVIDER_ON_THE_WAY,
            BookingStatus.PROVIDER_ARRIVED,
            BookingStatus.JOB_STARTED);

    static final BigDecimal MIN_FEE = new BigDecimal("0.00");
    static final BigDecimal MAX_FEE = new BigDecimal("999.99");

    private final SubcategoryCancellationFeePort feePort;
    private final BookingProperties properties;

    public CancellationFeePolicy(SubcategoryCancellationFeePort feePort, BookingProperties properties) {
        this.feePort = feePort;
        this.properties = properties;
    }

    /**
     * @return the cancellation fee to charge for cancelling {@code booking} from its current
     *         state (zero when fee-free)
     * @throws BookingException 409 if the current state cannot be cancelled at all, or 422 if
     *         the configured fee is outside 0.00-999.99
     */
    public BigDecimal feeFor(Booking booking) {
        BookingStatus state = booking.getStatus();
        if (FEE_FREE.contains(state)) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (FEE_CHARGED.contains(state)) {
            BigDecimal configured = feePort.cancellationFee(booking.getSubcategoryId())
                    .orElse(properties.getDefaultCancellationFee());
            return validateRange(configured);
        }
        // Not a cancellable state (e.g. JOB_COMPLETED onward). The state machine itself also
        // forbids the CANCELLED transition here, but we fail fast with a clear message.
        throw new BookingException(
                org.springframework.http.HttpStatus.CONFLICT,
                "CANCELLATION_NOT_ALLOWED",
                "Booking in state " + state + " can no longer be cancelled");
    }

    private BigDecimal validateRange(BigDecimal fee) {
        if (fee == null) {
            throw BookingException.validation("Cancellation fee is not configured");
        }
        if (fee.compareTo(MIN_FEE) < 0 || fee.compareTo(MAX_FEE) > 0) {
            throw BookingException.validation(
                    "Configured cancellation fee " + fee + " is outside the permitted range 0.00-999.99");
        }
        return fee.setScale(2);
    }
}
