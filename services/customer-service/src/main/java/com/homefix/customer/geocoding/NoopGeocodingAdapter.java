package com.homefix.customer.geocoding;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link GeocodingPort} adapter for local/dev environments.
 *
 * <p>It does not contact an external Maps API; it always reports that the coordinates
 * could not be resolved, exercising the graceful-degradation path (Requirement 2.2) where
 * the raw coordinates are stored with a warning. Real deployments supply a concrete Maps
 * adapter selected via {@code homefix.geocoding.provider}.
 *
 * <p>Activated when {@code homefix.geocoding.provider=noop} (the default) or when no other
 * {@link GeocodingPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.geocoding", name = "provider", havingValue = "noop", matchIfMissing = true)
public class NoopGeocodingAdapter implements GeocodingPort {

    @Override
    public Optional<String> reverseGeocode(double lat, double lng) {
        return Optional.empty();
    }
}
