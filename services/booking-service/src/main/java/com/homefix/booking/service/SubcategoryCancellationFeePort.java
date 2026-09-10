package com.homefix.booking.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Port supplying the per-Service_Subcategory cancellation fee (Requirement 9.18). Backed by
 * the Pricing Engine / Catalog configuration in production; a default adapter returns empty so
 * the {@link CancellationFeePolicy} falls back to the configured default fee.
 */
public interface SubcategoryCancellationFeePort {

    /**
     * @return the configured cancellation fee for the subcategory, or empty if none is
     *         configured (the policy then applies the platform default)
     */
    Optional<BigDecimal> cancellationFee(UUID subcategoryId);
}
