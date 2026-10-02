package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchSettings;
import com.homefix.dispatch.domain.DispatchSettingsValidationException;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads and applies the Admin Portal's dispatch rules (Requirements 8.2, 8.4, 8.5, 8.8, 19.5) at
 * runtime, without a restart.
 *
 * <p>The values live where {@link DispatchService} already reads them on every dispatch: the
 * weights in {@link MatchingWeightsStore} and the radius / cycle / timeout parameters in the
 * {@link DispatchProperties} bean. A dispatch reads the weights, the offer timeout and the cycle
 * count once when it starts and the radius once per cycle, so an update reaches every dispatch
 * that starts after it; one already running finishes with the timeout it began with.
 *
 * <p><strong>Not persisted.</strong> The Dispatch Engine has no database, so — exactly like the
 * weights endpoint before it — an update lasts until the next restart, after which the
 * {@code homefix.dispatch.*} configuration and {@code MatchingWeights.DEFAULT} apply again.
 *
 * <p>Every value is validated before anything is changed, so a rejected update leaves all five
 * settings as they were (the Requirement 19.5 guarantee, extended to the radius parameters).
 * Updates are serialised so two concurrent Admin saves cannot interleave field by field.
 */
@Service
public class DispatchSettingsService {

    private static final Logger log = LoggerFactory.getLogger(DispatchSettingsService.class);

    /** Upper bound on radius expansions: each cycle re-queries providers and re-offers. */
    public static final int MAX_EXPANSION_CYCLES = 10;

    /**
     * Offer-timeout bounds in seconds. Below 15 s a provider cannot realistically read and accept
     * the push; above 10 minutes a customer waits too long per declined provider, since the
     * timeout is also the provider lock TTL and the busy-provider wait (Requirements 8.5, 8.11).
     */
    public static final long MIN_OFFER_TIMEOUT_SECONDS = 15L;
    public static final long MAX_OFFER_TIMEOUT_SECONDS = 600L;

    private final MatchingWeightsStore weightsStore;
    private final DispatchProperties properties;

    public DispatchSettingsService(MatchingWeightsStore weightsStore, DispatchProperties properties) {
        this.weightsStore = weightsStore;
        this.properties = properties;
    }

    /** The settings new dispatches currently use. */
    public DispatchSettings current() {
        return new DispatchSettings(
                weightsStore.current(),
                properties.getInitialRadiusKm(),
                properties.getRadiusIncrementKm(),
                properties.getMaxExpansionCycles(),
                properties.getOfferTimeoutSeconds());
    }

    /**
     * Validates and, only if every value is valid, applies {@code settings}. The weights inside
     * are already valid by construction ({@code MatchingWeights.ofExact}).
     *
     * @return the now-active settings
     * @throws DispatchSettingsValidationException if a radius, cycle or timeout value is out of
     *                                             range; nothing is changed
     */
    public synchronized DispatchSettings update(DispatchSettings settings) {
        validate(settings);
        weightsStore.update(settings.weights());
        properties.setInitialRadiusKm(settings.initialRadiusKm());
        properties.setRadiusIncrementKm(settings.radiusIncrementKm());
        properties.setMaxExpansionCycles(settings.maxExpansionCycles());
        properties.setOfferTimeoutSeconds(settings.offerTimeoutSeconds());
        log.info("Dispatch settings updated: initialRadiusKm={}, radiusIncrementKm={}, maxExpansionCycles={}, "
                        + "offerTimeoutSeconds={}", settings.initialRadiusKm(), settings.radiusIncrementKm(),
                settings.maxExpansionCycles(), settings.offerTimeoutSeconds());
        return current();
    }

    private static void validate(DispatchSettings s) {
        if (!Double.isFinite(s.initialRadiusKm()) || s.initialRadiusKm() <= 0) {
            throw new DispatchSettingsValidationException(
                    "initialRadiusKm must be greater than 0 but was " + s.initialRadiusKm());
        }
        if (!Double.isFinite(s.radiusIncrementKm()) || s.radiusIncrementKm() < 0) {
            throw new DispatchSettingsValidationException(
                    "radiusIncrementKm must be 0 or greater but was " + s.radiusIncrementKm());
        }
        if (s.maxExpansionCycles() < 0 || s.maxExpansionCycles() > MAX_EXPANSION_CYCLES) {
            throw new DispatchSettingsValidationException("maxExpansionCycles must be in [0, "
                    + MAX_EXPANSION_CYCLES + "] but was " + s.maxExpansionCycles());
        }
        if (s.offerTimeoutSeconds() < MIN_OFFER_TIMEOUT_SECONDS
                || s.offerTimeoutSeconds() > MAX_OFFER_TIMEOUT_SECONDS) {
            throw new DispatchSettingsValidationException("offerTimeoutSeconds must be in ["
                    + MIN_OFFER_TIMEOUT_SECONDS + ", " + MAX_OFFER_TIMEOUT_SECONDS + "] but was "
                    + s.offerTimeoutSeconds());
        }
    }
}
