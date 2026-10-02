package com.homefix.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised dispatch tuning parameters (Requirements 8.2, 8.5, 8.8), so operations can adjust
 * behaviour without a code change. Defaults match the design and requirements.
 *
 * <p>The radius, cycle and offer-timeout values are also changed at runtime by the Admin Portal
 * ({@code DispatchSettingsService}) and read by {@code DispatchService} on dispatch threads, so
 * those fields are {@code volatile}: an Admin save on a request thread must be visible to the next
 * dispatch without further synchronisation. The poll interval is read once at startup and is not
 * runtime-tunable.
 */
@ConfigurationProperties(prefix = "homefix.dispatch")
public class DispatchProperties {

    /** Initial search radius in kilometres (Requirement 8.2, default 10 km). */
    private volatile double initialRadiusKm = 10.0;

    /** Radius increment added per expansion cycle in kilometres (Requirement 8.8, default 5 km). */
    private volatile double radiusIncrementKm = 5.0;

    /** Maximum number of expansion cycles beyond the initial search (Requirement 8.8, default 3). */
    private volatile int maxExpansionCycles = 3;

    /** Job-offer acceptance timeout in seconds; also the Redis lock TTL (Requirements 8.5, 8.11). */
    private volatile long offerTimeoutSeconds = 60L;

    /**
     * How often a waiting offer re-reads the provider's decision from Redis, in milliseconds. It
     * bounds how long an accept takes to reach the Booking Service, at one small read per interval
     * per in-flight offer.
     */
    private long offerPollIntervalMillis = 500L;

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

    public long getOfferPollIntervalMillis() {
        return offerPollIntervalMillis;
    }

    public void setOfferPollIntervalMillis(long offerPollIntervalMillis) {
        this.offerPollIntervalMillis = offerPollIntervalMillis;
    }
}
