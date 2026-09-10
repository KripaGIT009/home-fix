package com.homefix.dispatch.adapter;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.port.BookingTransitionPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link BookingTransitionPort} adapter that asks the Booking Service to perform the
 * PROVIDER_ACCEPTED and SEARCHING_FAILED transitions (Requirements 8.6, 8.9). The Booking Service
 * owns the state machine; the Dispatch Engine only requests the transition.
 *
 * <p>These transitions are on the critical dispatch path, so they run under the shared resilience
 * stack (Requirement 24): a 5 s per-call timeout (24.3), retry with exponential backoff on
 * transient failures (24.2), and a circuit breaker (24.1). When the {@code booking-service} breaker
 * is open â€” or every retry fails â€” the fallback emits a WARN log naming the dependency (24.4) and
 * raises {@link BookingTransitionException} so the dispatch loop treats the transition as a
 * recoverable degraded outcome rather than silently succeeding.
 *
 * <p>Active only when no other {@link BookingTransitionPort} bean is present (tests supply a fake).
 */
@Component
public class HttpBookingTransitionAdapter implements BookingTransitionPort {

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "booking-service";

    private final RestClient restClient;
    private final ResilientCall<Void> resilientCall;

    public HttpBookingTransitionAdapter(DispatchClientProperties properties, ResilienceFactory resilienceFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBookingServiceBaseUrl())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                }})
                .build();
        // On breaker-open or exhausted retries, signal a recoverable transition failure (Req 24.4).
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.CRITICAL_PATH,
                cause -> {
                    throw new BookingTransitionException(
                            "Booking Service transition unavailable (degraded)", cause);
                });
    }

    @Override
    public void markProviderAccepted(UUID bookingId, UUID providerId) {
        resilientCall.execute(() -> {
            restClient.post()
                    .uri("/internal/bookings/{id}/provider-accepted", bookingId)
                    .body(Map.of("providerId", providerId))
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Booking Service returned 5xx");
                    })
                    .toBodilessEntity();
            return null;
        });
    }

    @Override
    public void markSearchingFailed(UUID bookingId) {
        resilientCall.execute(() -> {
            restClient.post()
                    .uri("/internal/bookings/{id}/searching-failed", bookingId)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Booking Service returned 5xx");
                    })
                    .toBodilessEntity();
            return null;
        });
    }

    /** Unchecked wrapper so the dispatch loop can treat a transition failure as recoverable. */
    public static class BookingTransitionException extends RuntimeException {
        public BookingTransitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
