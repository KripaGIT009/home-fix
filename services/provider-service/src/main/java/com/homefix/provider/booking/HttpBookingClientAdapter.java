package com.homefix.provider.booking;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;

/**
 * {@link BookingClientPort} adapter that reads a provider's in-flight jobs from the Booking
 * Service's internal surface (Requirement 28.8).
 *
 * <p>Runs under the shared resilience stack (Requirement 24) — per-call timeout, retry with
 * backoff, and a circuit breaker keyed on {@code booking-service}. The dashboard is a read-only
 * panel, so the fallback degrades to an empty list and logs a WARN naming the dependency (24.4)
 * rather than failing the whole request: a provider still sees their wallet and today's earnings
 * when bookings are momentarily unreachable.
 *
 * <p>Activated with {@code homefix.booking.client=http}; the stub adapter is the default.
 */
@Component
@ConditionalOnProperty(name = "homefix.booking.client", havingValue = "http")
public class HttpBookingClientAdapter implements BookingClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpBookingClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "booking-service";

    /** Header carrying the shared service credential the Booking Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private static final ParameterizedTypeReference<List<ProviderJob>> JOB_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final ResilientCall<List<ProviderJob>> resilientCall;

    public HttpBookingClientAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.booking.base-url:http://booking-service:8084}") String baseUrl,
            @Value("${homefix.booking.internal-api-key:}") String internalApiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // Fail at startup rather than on the first dashboard load: without the credential every
            // call would be rejected with 401 and the panel would silently show no jobs.
            throw new IllegalStateException(
                    "homefix.booking.internal-api-key is not configured; the Provider Service cannot "
                            + "read active jobs from the Booking Service without it");
        }
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                .requestFactory(timeouts())
                .build();
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.STANDARD,
                cause -> {
                    log.warn("Booking Service unavailable ({}); active-job list degraded to empty",
                            DEPENDENCY, cause);
                    return List.of();
                });
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return factory;
    }

    @Override
    public List<ProviderJob> activeJobs(UUID providerId) {
        return resilientCall.execute(() -> {
            List<ProviderJob> jobs = restClient.get()
                    .uri("/internal/bookings/provider/{id}/active", providerId)
                    .retrieve()
                    .body(JOB_LIST);
            return jobs == null ? List.of() : jobs;
        });
    }
}
