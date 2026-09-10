package com.homefix.booking.pricing;

/**
 * Port abstracting the Pricing Engine (Task 13) client. The booking flow requests an itemized
 * estimate before asking the customer to confirm (Requirement 7.3).
 *
 * <p>Abstracted so it can be backed by a resilient HTTP adapter (circuit breaker + timeout,
 * Requirement 24.1-24.3) in production and a mock/stub in tests.
 */
public interface PricingClientPort {

    /**
     * Requests an itemized price estimate for the given booking inputs.
     *
     * @return the itemized estimate
     * @throws PricingUnavailableException if the Pricing Engine is unreachable or errors, in
     *         which case the booking MUST NOT be created (Requirement 7.4)
     */
    PriceEstimate estimate(PriceEstimateRequest request);

    /**
     * Submits the updated parts/materials total for recalculation when a provider adds parts
     * during job execution (Requirement 6.8, 11.3). Returns the updated itemized estimate
     * (with the {@code partsMaterialsCharge} component reflecting the new total).
     *
     * @throws PricingUnavailableException if the Pricing Engine is unreachable or errors
     */
    PriceEstimate recalculateWithParts(PartsRecalculationRequest request);
}
