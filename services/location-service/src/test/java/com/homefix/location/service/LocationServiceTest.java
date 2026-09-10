package com.homefix.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.homefix.location.config.LocationProperties;
import com.homefix.location.domain.Coordinates;
import com.homefix.location.domain.LocationHistory;
import com.homefix.location.domain.LocationHistoryRepository;
import com.homefix.location.domain.TerminatedBooking;
import com.homefix.location.domain.TerminatedBookingRepository;
import com.homefix.location.eta.EtaCalculatorPort;
import com.homefix.location.store.LocationStorePort;
import com.homefix.location.subscription.LocationUpdatePush;
import com.homefix.location.subscription.SubscriberRegistryPort;
import com.homefix.location.support.InMemoryLocationStore;
import com.homefix.location.support.MutableClock;

import org.mockito.Mockito;

/**
 * Unit tests for {@link LocationService} covering the four behaviours called out by Task 17:
 * the 1-per-5 s rate limit (Requirement 10.1), the staleness flag (Requirement 10.7),
 * subscription termination on JobStarted (Requirement 10.5), and the ETA push (Requirement
 * 10.4). Time is driven by a {@link MutableClock} so every decision is deterministic.
 */
class LocationServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final UUID bookingId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    private LocationStorePort store;
    private EtaCalculatorPort etaCalculator;
    private SubscriberRegistryPort subscribers;
    private LocationHistoryRepository historyRepository;
    private TerminatedBookingRepository terminatedRepository;
    private LocationProperties properties;
    private MutableClock clock;
    private LocationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLocationStore();
        etaCalculator = Mockito.mock(EtaCalculatorPort.class);
        subscribers = Mockito.mock(SubscriberRegistryPort.class);
        historyRepository = Mockito.mock(LocationHistoryRepository.class);
        terminatedRepository = new InMemoryTerminatedRepository();
        properties = new LocationProperties(); // 5 s interval, 60 s staleness
        clock = new MutableClock(T0, ZoneOffset.UTC);
        service = new LocationService(store, etaCalculator, subscribers, historyRepository,
                terminatedRepository, properties, clock);

        when(etaCalculator.etaMinutes(any(), any())).thenReturn(7);
    }

    private LocationUpdateCommand update(double lat, double lon) {
        return new LocationUpdateCommand(bookingId, providerId, new Coordinates(lat, lon));
    }

    // ---- Requirement 10.1: rate limiting (> 1 per 5 s rejected) ----

    @Test
    void firstUpdateIsAccepted() {
        LocationUpdatePush push = service.ingest(update(12.90, 77.60));

        assertThat(push.etaMinutes()).isEqualTo(7);
        verify(historyRepository, times(1)).save(any(LocationHistory.class));
        verify(subscribers, times(1)).push(eq(bookingId), any(LocationUpdatePush.class));
    }

    @Test
    void secondUpdateWithinFiveSecondsIsRejected() {
        service.ingest(update(12.90, 77.60));

        // 4 seconds later — inside the 5 s window.
        clock.advanceSeconds(4);

        assertThatThrownBy(() -> service.ingest(update(12.91, 77.61)))
                .isInstanceOf(LocationException.class)
                .satisfies(ex -> assertThat(((LocationException) ex).getErrorCode())
                        .isEqualTo("LOCATION_UPDATE_RATE_LIMITED"));

        // The rejected update must not be persisted or pushed.
        verify(historyRepository, times(1)).save(any(LocationHistory.class));
        verify(subscribers, times(1)).push(eq(bookingId), any(LocationUpdatePush.class));
    }

    @Test
    void updateExactlyAtFiveSecondBoundaryIsAccepted() {
        service.ingest(update(12.90, 77.60));

        clock.advanceSeconds(5); // exactly the minimum interval -> allowed

        LocationUpdatePush second = service.ingest(update(12.91, 77.61));

        assertThat(second).isNotNull();
        verify(historyRepository, times(2)).save(any(LocationHistory.class));
        verify(subscribers, times(2)).push(eq(bookingId), any(LocationUpdatePush.class));
    }

    @Test
    void updateAfterFiveSecondsIsAccepted() {
        service.ingest(update(12.90, 77.60));
        clock.advanceSeconds(6);

        service.ingest(update(12.91, 77.61));

        verify(historyRepository, times(2)).save(any(LocationHistory.class));
    }

    @Test
    void rateLimitIsPerProvider() {
        // A different provider on the same booking is not throttled by the first provider's update.
        service.ingest(update(12.90, 77.60));
        clock.advanceSeconds(1);

        UUID otherProvider = UUID.randomUUID();
        LocationUpdatePush push = service.ingest(
                new LocationUpdateCommand(bookingId, otherProvider, new Coordinates(13.0, 77.7)));

        assertThat(push).isNotNull();
        verify(historyRepository, times(2)).save(any(LocationHistory.class));
    }

    // ---- Requirement 10.4: ETA push ----

    @Test
    void pushesCoordinatesAndCalculatedEtaToSubscribers() {
        when(etaCalculator.etaMinutes(eq(bookingId), eq(new Coordinates(12.90, 77.60)))).thenReturn(12);

        service.ingest(update(12.90, 77.60));

        ArgumentCaptor<LocationUpdatePush> captor = ArgumentCaptor.forClass(LocationUpdatePush.class);
        verify(subscribers).push(eq(bookingId), captor.capture());
        assertThat(captor.getValue().etaMinutes()).isEqualTo(12);
        assertThat(captor.getValue().coordinates()).isEqualTo(new Coordinates(12.90, 77.60));
    }

    // ---- Requirement 10.3 / 10.7: tracking view + staleness ----

    @Test
    void trackingViewReturnsFreshLocationWhenRecent() {
        service.ingest(update(12.90, 77.60));
        clock.advanceSeconds(30); // within the 60 s staleness window

        TrackingView view = service.trackingView(bookingId);

        assertThat(view.stale()).isFalse();
        assertThat(view.coordinates()).isEqualTo(new Coordinates(12.90, 77.60));
        assertThat(view.lastUpdatedAt()).isEqualTo(T0);
        assertThat(view.providerId()).isEqualTo(providerId);
    }

    @Test
    void trackingViewFlagsStaleWhenLastUpdateOlderThanSixtySeconds() {
        service.ingest(update(12.90, 77.60));
        clock.advanceSeconds(61); // just past the 60 s window

        TrackingView view = service.trackingView(bookingId);

        assertThat(view.stale()).isTrue();
        // Last known coordinates are still returned, with the timestamp of the last update.
        assertThat(view.coordinates()).isEqualTo(new Coordinates(12.90, 77.60));
        assertThat(view.lastUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void trackingViewAtExactlySixtySecondsIsNotStale() {
        service.ingest(update(12.90, 77.60));
        clock.advanceSeconds(60); // boundary: not yet stale

        assertThat(service.trackingView(bookingId).stale()).isFalse();
    }

    @Test
    void trackingViewThrowsWhenNoLocationCached() {
        assertThatThrownBy(() -> service.trackingView(bookingId))
                .isInstanceOf(LocationException.class)
                .satisfies(ex -> assertThat(((LocationException) ex).getErrorCode())
                        .isEqualTo("LOCATION_NOT_AVAILABLE"));
    }

    // ---- Requirement 10.5: termination on JobStarted ----

    @Test
    void terminateClosesAllSubscriptionsAndEvictsCache() {
        service.ingest(update(12.90, 77.60));

        service.terminate(bookingId);

        verify(subscribers, times(1)).terminateAll(bookingId);
        assertThat(store.findLatest(bookingId)).isEmpty();
        assertThat(terminatedRepository.existsByBookingId(bookingId)).isTrue();
    }

    @Test
    void updatesRejectedAfterTermination() {
        service.terminate(bookingId);
        clock.advanceSeconds(120); // well past any rate-limit window

        assertThatThrownBy(() -> service.ingest(update(12.90, 77.60)))
                .isInstanceOf(LocationException.class)
                .satisfies(ex -> assertThat(((LocationException) ex).getErrorCode())
                        .isEqualTo("LOCATION_BOOKING_TERMINATED"));

        // A terminated booking must not persist or push anything.
        verify(historyRepository, never()).save(any());
        verify(subscribers, never()).push(any(), any());
    }

    @Test
    void terminateIsIdempotent() {
        service.terminate(bookingId);
        service.terminate(bookingId); // redelivered JobStarted -> no error, no duplicate marker

        assertThat(terminatedRepository.count()).isEqualTo(1);
        verify(subscribers, times(2)).terminateAll(bookingId);
    }

    /** In-memory stand-in for {@link TerminatedBookingRepository} used across the tests. */
    private static final class InMemoryTerminatedRepository
            extends com.homefix.location.support.AbstractInMemoryTerminatedRepository {
    }
}
