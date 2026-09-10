package com.homefix.location.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.location.config.LocationProperties;
import com.homefix.location.domain.CachedLocation;
import com.homefix.location.domain.Coordinates;
import com.homefix.location.domain.LocationHistory;
import com.homefix.location.domain.LocationHistoryRepository;
import com.homefix.location.domain.TerminatedBooking;
import com.homefix.location.domain.TerminatedBookingRepository;
import com.homefix.location.eta.EtaCalculatorPort;
import com.homefix.location.store.LocationStorePort;
import com.homefix.location.subscription.LocationUpdatePush;
import com.homefix.location.subscription.SubscriberRegistryPort;

/**
 * Core Location Service logic (Requirement 10).
 *
 * <p>On each accepted Provider update the service:
 * <ol>
 *   <li>Rejects the update if the Booking's feed has been terminated on JOB_STARTED
 *       (Requirement 10.5) or if it arrived within {@code minUpdateInterval} of the previous
 *       accepted update for the same Provider (max 1 per 5 s — Requirement 10.1);</li>
 *   <li>Stores the coordinates in the low-latency cache (Requirement 10.2) and persists them
 *       to the durable history for dispute resolution (Requirement 10.6);</li>
 *   <li>Calculates the ETA to the Customer address and pushes {@code {coordinates, eta}} to
 *       every subscriber of the Booking's feed (Requirement 10.2, 10.4).</li>
 * </ol>
 *
 * <p>All time-dependent decisions (the rate-limit window and the staleness window) are driven
 * by an injected {@link Clock} and the timestamps stored with each update, so the behaviour is
 * fully deterministic under test.
 */
@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final LocationStorePort locationStore;
    private final EtaCalculatorPort etaCalculator;
    private final SubscriberRegistryPort subscriberRegistry;
    private final LocationHistoryRepository historyRepository;
    private final TerminatedBookingRepository terminatedRepository;
    private final LocationProperties properties;
    private final Clock clock;

    public LocationService(LocationStorePort locationStore,
                           EtaCalculatorPort etaCalculator,
                           SubscriberRegistryPort subscriberRegistry,
                           LocationHistoryRepository historyRepository,
                           TerminatedBookingRepository terminatedRepository,
                           LocationProperties properties,
                           Clock clock) {
        this.locationStore = locationStore;
        this.etaCalculator = etaCalculator;
        this.subscriberRegistry = subscriberRegistry;
        this.historyRepository = historyRepository;
        this.terminatedRepository = terminatedRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Ingests a Provider location update, enforcing the JOB_STARTED gate and the 1-per-5 s
     * rate limit, then caches, persists, and pushes the update with a recalculated ETA.
     *
     * @return the payload that was pushed to subscribers (coordinates + ETA)
     * @throws LocationException if the Booking's feed is terminated or the update is rate-limited
     */
    @Transactional
    public LocationUpdatePush ingest(LocationUpdateCommand command) {
        UUID bookingId = command.bookingId();
        UUID providerId = command.providerId();

        if (terminatedRepository.existsByBookingId(bookingId)) {
            throw LocationException.bookingTerminated(
                    "Booking " + bookingId + " has started; location updates are no longer accepted");
        }

        Instant now = clock.instant();
        enforceRateLimit(bookingId, providerId, now);

        Coordinates coordinates = command.coordinates();

        // Persist to durable storage for dispute resolution (Requirement 10.6).
        historyRepository.save(LocationHistory.of(bookingId, providerId, coordinates, now));

        // Cache the last known location (Requirement 10.2).
        locationStore.save(new CachedLocation(bookingId, providerId, coordinates, now));

        // Recalculate ETA and push coordinates + ETA to all subscribers (Requirement 10.2, 10.4).
        int etaMinutes = etaCalculator.etaMinutes(bookingId, coordinates);
        LocationUpdatePush push = new LocationUpdatePush(coordinates, etaMinutes);
        subscriberRegistry.push(bookingId, push);

        log.debug("Ingested location for booking {} provider {} eta={}min subscribers={}",
                bookingId, providerId, etaMinutes, subscriberRegistry.subscriberCount(bookingId));
        return push;
    }

    /**
     * Returns the most recently stored Provider coordinates for a Booking, flagged {@code stale}
     * when the last update is older than the staleness threshold (Requirement 10.3, 10.7).
     *
     * @throws LocationException if no location has ever been cached for the Booking
     */
    @Transactional(readOnly = true)
    public TrackingView trackingView(UUID bookingId) {
        Optional<CachedLocation> latest = locationStore.findLatest(bookingId);
        if (latest.isEmpty()) {
            throw LocationException.noLocation(
                    "No location available yet for booking " + bookingId);
        }
        CachedLocation location = latest.get();
        boolean stale = isStale(location.recordedAt(), clock.instant());
        return new TrackingView(bookingId, location.providerId(), location.coordinates(),
                location.recordedAt(), stale);
    }

    /**
     * Terminates a Booking's location feed on JOB_STARTED (Requirement 10.5): records the
     * termination marker so further updates are rejected, closes all subscriber sessions, and
     * evicts the cached location. Idempotent — a redelivered JobStarted event is a no-op.
     */
    @Transactional
    public void terminate(UUID bookingId) {
        if (!terminatedRepository.existsByBookingId(bookingId)) {
            terminatedRepository.save(new TerminatedBooking(bookingId, clock.instant()));
        }
        subscriberRegistry.terminateAll(bookingId);
        locationStore.evict(bookingId);
        log.info("Terminated location feed for booking {} on JOB_STARTED", bookingId);
    }

    private void enforceRateLimit(UUID bookingId, UUID providerId, Instant now) {
        Duration minInterval = properties.getMinUpdateInterval();
        locationStore.findLatest(bookingId)
                .filter(latest -> latest.providerId().equals(providerId))
                .ifPresent(latest -> {
                    Instant earliestAllowed = latest.recordedAt().plus(minInterval);
                    if (now.isBefore(earliestAllowed)) {
                        throw LocationException.rateLimited(
                                "Location updates limited to one per " + minInterval.toSeconds()
                                        + "s per provider per booking; next accepted at " + earliestAllowed);
                    }
                });
    }

    private boolean isStale(Instant lastUpdatedAt, Instant now) {
        Instant staleAfter = lastUpdatedAt.plus(properties.getStalenessThreshold());
        return now.isAfter(staleAfter);
    }
}
