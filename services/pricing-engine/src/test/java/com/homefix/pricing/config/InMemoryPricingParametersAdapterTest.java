package com.homefix.pricing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Unit tests for the default in-memory pricing-parameters adapter (Requirement 6.11): save
 * persists by subcategory and lookups return the latest saved value, missing when unknown.
 */
class InMemoryPricingParametersAdapterTest {

    private final InMemoryPricingParametersAdapter adapter = new InMemoryPricingParametersAdapter();

    @Test
    void findMissesWhenNotSaved() {
        assertThat(adapter.findBySubcategoryId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void saveThenFindReturnsStoredParameters() {
        PricingParameters saved = adapter.save(baseParams("100.00"));

        assertThat(saved.basePrice()).isEqualByComparingTo("100.00");
        assertThat(adapter.findBySubcategoryId(SUBCATEGORY)).contains(saved);
    }

    @Test
    void saveOverwritesPreviousValueForSameSubcategory() {
        adapter.save(baseParams("100.00"));
        adapter.save(baseParams("150.00"));

        PricingParameters got = adapter.findBySubcategoryId(SUBCATEGORY).orElseThrow();
        assertThat(got.basePrice()).isEqualByComparingTo("150.00");
    }
}
