package com.homefix.location.booking;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.homefix.location.service.LocationException;
import com.homefix.shared.resilience.FallbackDecision;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link BookingParticipantsPort}: the Booking Service's internal
 * {@code GET /internal/bookings/{id}/payment-facts}, sent with the shared {@code X-Internal-Api-Key}
 * (the same {@code INTERNAL_API_KEY} the Booking Service checks). That endpoint already answers the
 * booking's customer, provider and status for the Payment Service, so no new Booking Service
 * endpoint is needed; the amount and reference it also carries are ignored here.
 *
 * <p>Tracking is a customer request path, so the call runs under the shared resilience stack with
 * the {@link TimeoutProfile#CRITICAL_PATH} budget, retry for transient failures and one circuit
 * breaker keyed on {@value #DEPENDENCY}.
 *
 * <p>A 404 is an answer (the booking does not exist) and is returned as empty, never retried.
 * Everything else the Booking Service cannot answer (unreachable, timed out, 5xx, breaker open, the
 * credential refused, an unreadable body) fails <em>closed</em> as
 * {@code 503 LOCATION_BOOKING_LOOKUP_UNAVAILABLE}: no position is shown or recorded for a booking
 * whose participants could not be confirmed. A missing credential is logged as an ERROR at startup
 * and every lookup then answers 503 without a network call (staff reads, which need no lookup, keep
 * working).
 */
public class HttpBookingParticipantsAdapter implements BookingParticipantsPort {

    private static final Logger log = LoggerFactory.getLogger(HttpBookingParticipantsAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "booking-service";

    /** Header carrying the shared service credential the Booking Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    /** The Booking Service endpoint that answers who a booking is between. */
    static final String PARTICIPANTS_PATH = "/internal/bookings/{bookingId}/payment-facts";

    private final RestClient restClient;
    private final ResilientCall<Optional<BookingParticipants>> lookup;
    private final boolean configured;

    public HttpBookingParticipantsAdapter(ResilienceFactory resilienceFactory, String baseUrl,
                                          String internalApiKey) {
        this(resilienceFactory, baseUrl, internalApiKey, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpBookingParticipantsAdapter(ResilienceFactory resilienceFactory, String baseUrl,
                                   String internalApiKey, Duration timeout) {
        this.configured = internalApiKey != null && !internalApiKey.isBlank();
        if (!configured) {
            log.error("homefix.location.internal-api-key (INTERNAL_API_KEY) is not configured; the "
                    + "Booking Service would refuse every participant lookup, so only staff can read "
                    + "locations and no provider can post one");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (configured) {
            builder.defaultHeader(INTERNAL_KEY_HEADER, internalApiKey);
        }
        this.restClient = builder.build();
        this.lookup = ResilientCall.forDependency(resilienceFactory, DEPENDENCY, timeout, failClosed());
    }

    @Override
    public Optional<BookingParticipants> participants(UUID bookingId) {
        if (!configured) {
            throw unavailable();
        }
        return lookup.execute(() -> restClient.get()
                .uri(PARTICIPANTS_PATH, bookingId)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_FOUND.value()) {
                        return Optional.<BookingParticipants>empty();
                    }
                    requireSuccess(status);
                    BookingParticipants participants = response.bodyTo(BookingParticipants.class);
                    if (participants == null || participants.bookingId() == null) {
                        throw new IllegalStateException("Booking Service returned no booking");
                    }
                    return Optional.of(participants);
                }));
    }

    /** 5xx is transient (retried, counted by the breaker); any other non-2xx is a refusal. */
    private static void requireSuccess(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            throw new TransientFailures.ServerErrorException(status.value(),
                    "Booking Service returned " + status.value());
        }
        if (!status.is2xxSuccessful()) {
            // 401/403 = credential refused; anything else is a contract mismatch. Not transient.
            throw new IllegalStateException("Booking Service refused the request with HTTP " + status.value());
        }
    }

    private static <T> FallbackDecision<T> failClosed() {
        return cause -> {
            log.warn("Booking Service unavailable ({}); refusing the location request: {}",
                    DEPENDENCY, String.valueOf(cause));
            throw unavailable();
        };
    }

    private static LocationException unavailable() {
        return LocationException.bookingLookupUnavailable(
                "The Booking Service is unavailable; the booking's participants could not be checked");
    }
}
