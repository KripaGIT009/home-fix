package com.homefix.booking.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.booking.catalog.StubCatalogClientAdapter;
import com.homefix.booking.media.LocalMediaStorageAdapter;
import com.homefix.booking.media.MediaFile;
import com.homefix.booking.pricing.PartsRecalculationRequest;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.pricing.PriceEstimateRequest;
import com.homefix.booking.pricing.StubPricingClientAdapter;
import com.homefix.booking.service.DefaultSubcategoryCancellationFeeAdapter;

/**
 * Unit tests for the default local/dev adapters. They keep the booking flow functional without
 * external systems: the catalog stub treats well-formed references as active, the pricing stub
 * returns a deterministic itemized estimate, the local media adapter derives an S3-shaped key,
 * and the default cancellation-fee adapter returns no per-subcategory override.
 */
class StubAdaptersTest {

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID SUB = UUID.randomUUID();

    @Test
    void catalogStubTreatsWellFormedReferencesAsActive() {
        StubCatalogClientAdapter catalog = new StubCatalogClientAdapter();

        assertThat(catalog.isSubcategoryActive(CATEGORY, SUB)).isTrue();
        assertThat(catalog.isSubcategoryActive(null, SUB)).isFalse();
        assertThat(catalog.isSubcategoryActive(CATEGORY, null)).isFalse();
    }

    @Test
    void pricingStubReturnsItemizedEstimateWhoseComponentsSumToTotal() {
        StubPricingClientAdapter pricing = new StubPricingClientAdapter();
        PriceEstimateRequest req = new PriceEstimateRequest(CATEGORY, SUB, UUID.randomUUID(),
                false, java.time.Instant.now(), null);

        PriceEstimate estimate = pricing.estimate(req);

        assertThat(estimate.total()).isNotNull();
        assertThat(estimate.components()).containsKeys("basePrice", "platformFee", "taxes");
        BigDecimal sum = estimate.components().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        assertThat(sum).isEqualByComparingTo(estimate.total());
    }

    @Test
    void pricingStubAppliesEmergencyComponentWhenEmergency() {
        StubPricingClientAdapter pricing = new StubPricingClientAdapter();

        PriceEstimate normal = pricing.estimate(
                new PriceEstimateRequest(CATEGORY, SUB, UUID.randomUUID(), false, java.time.Instant.now(), null));
        PriceEstimate emergency = pricing.estimate(
                new PriceEstimateRequest(CATEGORY, SUB, UUID.randomUUID(), true, java.time.Instant.now(), null));

        assertThat(emergency.components().get("emergencyCharge")).isGreaterThan(BigDecimal.ZERO);
        assertThat(emergency.total()).isGreaterThan(normal.total());
    }

    @Test
    void pricingStubRecalculatesByAddingPartsToOriginal() {
        StubPricingClientAdapter pricing = new StubPricingClientAdapter();
        PartsRecalculationRequest req = new PartsRecalculationRequest(UUID.randomUUID(),
                CATEGORY, SUB, new BigDecimal("100.00"), new BigDecimal("25.00"),
                false, java.time.Instant.parse("2026-01-05T10:00:00Z"));

        PriceEstimate updated = pricing.recalculateWithParts(req);

        assertThat(updated.total()).isEqualByComparingTo("125.00");
        assertThat(updated.components().get("partsMaterialsCharge")).isEqualByComparingTo("25.00");
    }

    @Test
    void pricingStubRecalculationTreatsNullsAsZero() {
        StubPricingClientAdapter pricing = new StubPricingClientAdapter();
        PartsRecalculationRequest req = new PartsRecalculationRequest(UUID.randomUUID(),
                CATEGORY, SUB, null, null,
                false, java.time.Instant.parse("2026-01-05T10:00:00Z"));

        PriceEstimate updated = pricing.recalculateWithParts(req);

        assertThat(updated.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void localMediaAdapterDerivesBookingScopedKey() {
        LocalMediaStorageAdapter storage = new LocalMediaStorageAdapter();
        UUID booking = UUID.randomUUID();
        MediaFile file = new MediaFile("photo.jpg", "image/jpeg", 100L, new byte[]{1, 2, 3});

        String key = storage.store(booking, file);

        assertThat(key).startsWith("bookings/" + booking + "/media/");
    }

    @Test
    void defaultCancellationFeeAdapterReturnsEmpty() {
        DefaultSubcategoryCancellationFeeAdapter adapter = new DefaultSubcategoryCancellationFeeAdapter();

        assertThat(adapter.cancellationFee(SUB)).isEmpty();
    }
}
