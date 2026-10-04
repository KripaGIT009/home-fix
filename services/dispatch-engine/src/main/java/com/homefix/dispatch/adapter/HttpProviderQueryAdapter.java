package com.homefix.dispatch.adapter;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ProviderSearchUnavailableException;
import com.homefix.dispatch.domain.ScoreComponents;
import com.homefix.dispatch.port.ProviderQueryPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link ProviderQueryPort} adapter that queries the Provider Service over HTTP for
 * eligible providers within a radius (Requirement 8.2). The Provider Service applies the
 * eligibility filters and returns each provider's five component scores.
 *
 * <p>The query is on the critical dispatch path, so it runs under the shared resilience stack
 * (Requirement 24): a 5 s per-call timeout (24.3), retry with exponential backoff for transient
 * failures (24.2), and a circuit breaker (24.1). When the {@code provider-service} breaker is open
 * — or every retry fails, or the call is refused — a WARN log naming the dependency is emitted
 * (24.4) and {@link ProviderSearchUnavailableException} is raised.
 *
 * <p>Only a 200 is an answer about the market. The fallback used to return an empty candidate
 * list, so an outage looked exactly like "nobody nearby": the dispatch loop walked every radius,
 * found no one, and failed the booking as "no provider available". A refused credential did the
 * same to every booking, silently. The dispatch loop now tells the two apart and treats an
 * unavailable search as an outage rather than as a verdict (see {@code DispatchService}).
 *
 * <p>{@code /internal/providers/eligible} is service-to-service only: every call presents the
 * shared {@code X-Internal-Api-Key}, the same {@code homefix.dispatch.internal-api-key} this service
 * sends to the Booking and Customer Services. A missing key fails startup; a refused one (401/403)
 * is a deployment error, so it is logged at ERROR and not retried here — retrying the same key
 * cannot help — and surfaces as {@link ProviderSearchUnavailableException} like any other outage.
 *
 * <p>Active only when no other {@link ProviderQueryPort} bean is present (tests supply a fake).
 */
@Component
public class HttpProviderQueryAdapter implements ProviderQueryPort {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderQueryAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "provider-service";

    /** Header carrying the shared service credential the Provider Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final ResilientCall<List<ProviderCandidate>> resilientCall;

    public HttpProviderQueryAdapter(DispatchClientProperties properties,
                                    ResilienceFactory resilienceFactory,
                                    @Value("${homefix.dispatch.internal-api-key:}") String internalApiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException(
                    "homefix.dispatch.internal-api-key is not configured; the Dispatch Engine cannot "
                            + "query the Provider Service for eligible providers without it");
        }
        this.restClient = RestClient.builder()
                .baseUrl(properties.getProviderServiceBaseUrl())
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                // Provider matching is on the critical dispatch path: cap at 5 s (Requirement 24.3).
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                }})
                .build();
        // An unavailable dependency is not an empty market: report it (Requirement 24.4).
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.CRITICAL_PATH,
                HttpProviderQueryAdapter::unavailable);
    }

    @Override
    public List<ProviderCandidate> findEligibleProviders(DispatchRequest request, double radiusKm) {
        return resilientCall.execute(() -> {
            EligibleProvidersResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/providers/eligible")
                            .queryParam("subcategoryId", request.subcategoryId())
                            .queryParam("lat", request.customerLat())
                            .queryParam("lon", request.customerLon())
                            .queryParam("radiusKm", radiusKm)
                            .queryParam("emergency", request.emergency())
                            .queryParam("skillTags", request.requiredSkillTags().toArray())
                            .build())
                    .retrieve()
                    // 5xx is a transient failure: surface it so retry/breaker act on it (Req 24.2).
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Provider Service returned 5xx");
                    })
                    // A refused credential is a deployment error, not an outage: say so loudly.
                    .onStatus(status -> status.value() == 401 || status.value() == 403, (req, res) -> {
                        int status = res.getStatusCode().value();
                        log.error("Provider Service refused the eligible-provider query with HTTP {}; "
                                + "check that INTERNAL_API_KEY matches across services", status);
                        throw new IllegalStateException(
                                "Provider Service eligible-provider query returned HTTP " + status);
                    })
                    .body(EligibleProvidersResponse.class);
            if (response == null || response.providers() == null) {
                return List.of();
            }
            List<ProviderCandidate> candidates = new ArrayList<>(response.providers().size());
            for (EligibleProvider p : response.providers()) {
                candidates.add(new ProviderCandidate(p.providerId(), new ScoreComponents(
                        p.distanceScore(), p.availabilityScore(), p.ratingScore(),
                        p.skillScore(), p.performanceScore())));
            }
            return candidates;
        });
    }

    /** Fallback: every failure, the open breaker included, means the market is unknown. */
    private static List<ProviderCandidate> unavailable(Throwable cause) {
        throw new ProviderSearchUnavailableException(
                "Provider Service eligible-provider query unavailable: " + cause, cause);
    }

    /** Response shape from the Provider Service internal endpoint. */
    public record EligibleProvidersResponse(List<EligibleProvider> providers) {
    }

    /** A single eligible provider with its component scores. */
    public record EligibleProvider(
            UUID providerId,
            double distanceScore,
            double availabilityScore,
            double ratingScore,
            double skillScore,
            double performanceScore) {
    }
}
