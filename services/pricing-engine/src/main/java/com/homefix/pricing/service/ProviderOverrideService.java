package com.homefix.pricing.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Validation of provider-specific pricing overrides (Requirement 6.12, Property 7).
 *
 * <p>A provider may propose a custom base price for a subcategory. The override is rejected if
 * and only if the proposed price is below the platform-defined floor or above the ceiling for
 * that subcategory; otherwise it is accepted. On rejection the error specifies the permitted
 * range.
 */
@Service
public class ProviderOverrideService {

    /**
     * Validates a provider-specific override against the subcategory floor/ceiling.
     *
     * @return the accepted override price (unchanged) when within range.
     * @throws PricingException with error code {@code OVERRIDE_OUT_OF_RANGE} when out of range.
     */
    public BigDecimal validateOverride(BigDecimal proposedPrice, PricingParameters params) {
        if (proposedPrice == null) {
            throw PricingException.validation("Override price is required");
        }
        BigDecimal floor = params.overrideFloor();
        BigDecimal ceiling = params.overrideCeiling();

        boolean belowFloor = floor != null && proposedPrice.compareTo(floor) < 0;
        boolean aboveCeiling = ceiling != null && proposedPrice.compareTo(ceiling) > 0;

        if (belowFloor || aboveCeiling) {
            throw PricingException.overrideOutOfRange(
                    "Provider-specific price " + proposedPrice + " is outside the permitted range ["
                            + floor + ", " + ceiling + "] for subcategory " + params.subcategoryId(),
                    List.of("floor=" + floor, "ceiling=" + ceiling, "proposed=" + proposedPrice));
        }
        return proposedPrice;
    }
}
