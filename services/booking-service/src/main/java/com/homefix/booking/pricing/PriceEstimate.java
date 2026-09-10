package com.homefix.booking.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Itemized price estimate returned by the Pricing Engine (Requirement 7.3, 6.9). The
 * {@code components} map preserves each labelled charge/discount separately so the Booking
 * Service can present a fully itemized estimate to the customer before confirmation.
 */
public record PriceEstimate(BigDecimal total, Map<String, BigDecimal> components) {

    public PriceEstimate {
        if (total == null) {
            throw new IllegalArgumentException("total must not be null");
        }
        components = components == null ? Map.of() : Map.copyOf(components);
    }

    /** Ordered list of the canonical price components shown to the customer. */
    public static final List<String> COMPONENT_LABELS = List.of(
            "basePrice", "distanceCharge", "timeCharge", "partsMaterialsCharge", "emergencyCharge",
            "weekendSurcharge", "nightSurcharge", "demandSurgeCharge", "platformFee", "taxes",
            "discountAmount", "couponAmount");
}
