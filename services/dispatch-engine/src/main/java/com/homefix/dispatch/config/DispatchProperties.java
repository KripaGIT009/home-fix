package com.homefix.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised dispatch tuning parameters (Requirements 8.2, 8.5, 8.8), so operations can adjust
 * behaviour without a code change. Defaults match the design and requirements.
 */
@ConfigurationProperties(prefix = "homefix.dispatch")
public class DispatchProperties {

    /** Initial search radius in kilometres (Requirement 8.2, default 10 km). */
    private double initialRadiusKm = 10.0;

    /** Radius increment added per expansion cycle in kilometres (Requirement 8.8, default 5 km). */
    private double radiusIncrementKm = 5.0;

    /** Maximum number of expansion cycles beyond the initial search (Requirement 8.8, default 3). */
    private int maxExpansionCycles = 3;

    /** Job-offer acceptance timeout in seconds; also the Redis lock TTL (Requirements 8.5, 8.11). */
    private long offerTimeoutSeconds = 60L;

    public double getInitialRadiusKm() {
        return initialRadiusKm;
    }

    public void setInitialRadiusKm(double initialRadiusKm) {
        this.initialRadiusKm = initialRadiusKm;
    }

    public double getRadiusIncrementKm() {
        return radiusIncrementKm;
    }

    public void setRadiusIncrementKm(double radiusIncrementKm) {
        this.radiusIncrementKm = radiusIncrementKm;
    }

    public int getMaxExpansionCycles() {
        return maxExpansionCycles;
    }

    public void setMaxExpansionCycles(int maxExpansionCycles) {
        this.maxExpansionCycles = maxExpansionCycles;
    }

    public long getOfferTimeoutSeconds() {
        return offerTimeoutSeconds;
    }

    public void setOfferTimeoutSeconds(long offerTimeoutSeconds) {
        this.offerTimeoutSeconds = offerTimeoutSeconds;
    }
}
