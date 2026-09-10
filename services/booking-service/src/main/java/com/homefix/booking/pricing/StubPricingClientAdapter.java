package com.homefix.booking.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development {@link PricingClientPort} returning a deterministic itemized estimate, so the
 * booking flow is functional without a Pricing Engine to talk to.
 *
 * <p>Selecting {@code homefix.pricing.client=http} swaps in {@link HttpPricingClientAdapter},
 * which quotes against the real Pricing Engine; supplying a mock in tests replaces either
 * without touching the booking business logic. Tests that simulate Pricing Engine
 * unavailability supply a stub that throws {@link PricingUnavailableException}.
 */
@Component
@ConditionalOnProperty(name = "homefix.pricing.client", havingValue = "stub", matchIfMissing = true)
public class StubPricingClientAdapter implements PricingClientPort {

    private static final Logger log = LoggerFactory.getLogger(StubPricingClientAdapter.class);

    @Override
    public PriceEstimate estimate(PriceEstimateRequest request) {
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        BigDecimal base = new BigDecimal("500.00");
        components.put("basePrice", base);
        components.put("distanceCharge", new BigDecimal("50.00"));
        components.put("timeCharge", BigDecimal.ZERO.setScale(2));
        components.put("partsMaterialsCharge", BigDecimal.ZERO.setScale(2));
        components.put("emergencyCharge", request.emergency() ? new BigDecimal("500.00") : BigDecimal.ZERO.setScale(2));
        components.put("weekendSurcharge", BigDecimal.ZERO.setScale(2));
        components.put("nightSurcharge", BigDecimal.ZERO.setScale(2));
        components.put("demandSurgeCharge", BigDecimal.ZERO.setScale(2));
        components.put("platformFee", new BigDecimal("55.00"));
        components.put("taxes", new BigDecimal("99.00"));
        components.put("discountAmount", BigDecimal.ZERO.setScale(2));
        components.put("couponAmount", BigDecimal.ZERO.setScale(2));

        BigDecimal total = components.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        log.debug("Stub pricing: estimate total={} emergency={}", total, request.emergency());
        return new PriceEstimate(total, components);
    }

    @Override
    public PriceEstimate recalculateWithParts(PartsRecalculationRequest request) {
        BigDecimal original = request.originalTotal() == null
                ? BigDecimal.ZERO.setScale(2) : request.originalTotal();
        BigDecimal parts = request.partsTotal() == null
                ? BigDecimal.ZERO.setScale(2) : request.partsTotal().setScale(2);
        // The stub adds the parts total on top of the original estimate.
        BigDecimal updated = original.add(parts).setScale(2);
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put("originalTotal", original.setScale(2));
        components.put("partsMaterialsCharge", parts);
        log.debug("Stub pricing: parts recalculation original={} parts={} updated={}",
                original, parts, updated);
        return new PriceEstimate(updated, components);
    }
}
