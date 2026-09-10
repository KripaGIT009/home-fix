package com.homefix.location.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised location-service configuration (bound from {@code homefix.location.*}).
 */
@ConfigurationProperties(prefix = "homefix.location")
public class LocationProperties {

    /**
     * Minimum interval between accepted Provider location updates for the same Provider on the
     * same active Booking (Requirement 10.1). Updates arriving sooner than this are rejected.
     */
    private Duration minUpdateInterval = Duration.ofSeconds(5);

    /**
     * Age after which a cached location is flagged as stale in the tracking view
     * (Requirement 10.7). The last known coordinates are still returned, annotated with the
     * timestamp of the last update.
     */
    private Duration stalenessThreshold = Duration.ofSeconds(60);

    /**
     * Time-to-live for the Redis last-known-location entry. Entries are refreshed on every
     * accepted update; a booking that goes quiet eventually expires from the cache while the
     * durable history remains in PostgreSQL.
     */
    private Duration cacheTtl = Duration.ofMinutes(30);

    public Duration getMinUpdateInterval() {
        return minUpdateInterval;
    }

    public void setMinUpdateInterval(Duration minUpdateInterval) {
        this.minUpdateInterval = minUpdateInterval;
    }

    public Duration getStalenessThreshold() {
        return stalenessThreshold;
    }

    public void setStalenessThreshold(Duration stalenessThreshold) {
        this.stalenessThreshold = stalenessThreshold;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
