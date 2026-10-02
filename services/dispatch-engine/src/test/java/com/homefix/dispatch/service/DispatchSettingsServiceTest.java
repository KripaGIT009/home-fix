package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchSettings;
import com.homefix.dispatch.domain.DispatchSettingsValidationException;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DispatchSettingsService} (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): an update
 * lands in the very objects {@code DispatchService} reads per dispatch, and any out-of-range value
 * rejects the whole update without changing a single setting.
 */
class DispatchSettingsServiceTest {

    private static final MatchingWeights NEW_WEIGHTS = MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10);

    private MatchingWeightsStore weightsStore;
    private DispatchProperties properties;
    private DispatchSettingsService service;

    @BeforeEach
    void setUp() {
        weightsStore = new MatchingWeightsStore();
        properties = new DispatchProperties();
        service = new DispatchSettingsService(weightsStore, properties);
    }

    @Test
    void currentReflectsTheDefaults() {
        DispatchSettings current = service.current();

        assertThat(current.weights()).isEqualTo(MatchingWeights.DEFAULT);
        assertThat(current.initialRadiusKm()).isEqualTo(10.0);
        assertThat(current.radiusIncrementKm()).isEqualTo(5.0);
        assertThat(current.maxExpansionCycles()).isEqualTo(3);
        assertThat(current.offerTimeoutSeconds()).isEqualTo(60L);
    }

    @Test
    void updateAppliesToTheLiveWeightsStoreAndProperties() {
        DispatchSettings applied = service.update(new DispatchSettings(NEW_WEIGHTS, 7.5, 2.5, 5, 90));

        assertThat(weightsStore.current()).isEqualTo(NEW_WEIGHTS);
        assertThat(properties.getInitialRadiusKm()).isEqualTo(7.5);
        assertThat(properties.getRadiusIncrementKm()).isEqualTo(2.5);
        assertThat(properties.getMaxExpansionCycles()).isEqualTo(5);
        assertThat(properties.getOfferTimeoutSeconds()).isEqualTo(90L);
        assertThat(applied).isEqualTo(service.current());
    }

    @Test
    void boundaryValuesAreAccepted() {
        service.update(new DispatchSettings(NEW_WEIGHTS, 0.1, 0.0, 0, 15));
        assertThat(properties.getMaxExpansionCycles()).isZero();

        service.update(new DispatchSettings(NEW_WEIGHTS, 50.0, 10.0, 10, 600));
        assertThat(properties.getOfferTimeoutSeconds()).isEqualTo(600L);
    }

    @Test
    void anyInvalidValueRejectsTheWholeUpdate() {
        DispatchSettings before = service.current();

        assertRejected(new DispatchSettings(NEW_WEIGHTS, 0.0, 5.0, 3, 60), "initialRadiusKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, Double.NaN, 5.0, 3, 60), "initialRadiusKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, -1.0, 3, 60), "radiusIncrementKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, -1, 60), "maxExpansionCycles");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 11, 60), "maxExpansionCycles");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 14), "offerTimeoutSeconds");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 601), "offerTimeoutSeconds");

        // Nothing changed — not even the (valid) weights that travelled with the bad value.
        assertThat(service.current()).isEqualTo(before);
    }

    private void assertRejected(DispatchSettings settings, String field) {
        assertThatThrownBy(() -> service.update(settings))
                .isInstanceOf(DispatchSettingsValidationException.class)
                .hasMessageContaining(field);
    }
}
