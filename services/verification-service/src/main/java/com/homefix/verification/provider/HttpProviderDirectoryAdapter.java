package com.homefix.verification.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * {@link ProviderDirectoryPort} adapter over the Provider Service's internal batch endpoint
 * {@code GET /internal/providers/summaries?ids=...}, presenting the shared
 * {@code X-Internal-Api-Key} credential. It mirrors the Provider Service's own adapter for this
 * service's {@code /internal/verifications/approved}.
 *
 * <p>One call per queue listing, not one per row; ids are sent in chunks of
 * {@value #MAX_IDS_PER_REQUEST}, the Provider Service's per-request cap (the queue itself is
 * capped at the same size, so in practice it is a single call).
 *
 * <p>The call runs under the shared resilience stack (Requirement 24): retry for transient
 * failures, a circuit breaker keyed on {@value #DEPENDENCY}, and the
 * {@link TimeoutProfile#CRITICAL_PATH} budget, which also bounds the socket connect and read. That
 * is shorter than the standard ceiling on purpose: an Admin is waiting on the page and the data is
 * only cosmetic, so it should degrade quickly rather than hold the queue for 15 s per attempt.
 *
 * <p><strong>Fails soft.</strong> When the Provider Service is unreachable, refuses the
 * credential, or the key is not configured, the adapter returns no summaries and the queue is
 * shown with blank names. Nothing about the verification decision depends on these fields.
 */
@Component
public class HttpProviderDirectoryAdapter implements ProviderDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderDirectoryAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "provider-service";

    /** Header carrying the shared service credential the Provider Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    /** Matches the Provider Service's cap on ids per summaries request. */
    static final int MAX_IDS_PER_REQUEST = 200;

    private final RestClient restClient;
    private final ResilientCall<Map<UUID, ProviderSummary>> resilientCall;
    private final boolean configured;

    @Autowired
    public HttpProviderDirectoryAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.provider.base-url:http://provider-service:8083}") String baseUrl,
            @Value("${homefix.provider.internal-api-key:}") String internalApiKey) {
        this(resilienceFactory, baseUrl, internalApiKey, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpProviderDirectoryAdapter(ResilienceFactory resilienceFactory, String baseUrl,
                                 String internalApiKey, Duration timeout) {
        this.configured = internalApiKey != null && !internalApiKey.isBlank();
        if (!configured) {
            log.error("homefix.provider.internal-api-key is not configured; the verification review "
                    + "queue will be shown without provider names");
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
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, timeout,
                cause -> {
                    log.warn("Provider Service unavailable ({}); showing the review queue without "
                            + "provider names", DEPENDENCY, cause);
                    return Map.of();
                });
    }

    @Override
    public Map<UUID, ProviderSummary> summariesOf(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty() || !configured) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(providerIds));
        Map<UUID, ProviderSummary> summaries = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_REQUEST) {
            List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_REQUEST, ids.size()));
            summaries.putAll(resilientCall.execute(() -> fetchSummaries(chunk)));
        }
        // Never trust the response to stay within the question: only ids we asked about count.
        summaries.keySet().retainAll(ids);
        return summaries;
    }

    private Map<UUID, ProviderSummary> fetchSummaries(List<UUID> chunk) {
        ProviderSummariesResponse response = restClient.get()
                .uri(uri -> uri.path("/internal/providers/summaries")
                        .queryParam("ids", chunk.stream().map(UUID::toString).toArray())
                        .build())
                .retrieve()
                // 5xx is a transient failure: surface it so retry/breaker act on it (Req 24.2).
                .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                    throw new TransientFailures.ServerErrorException(
                            res.getStatusCode().value(), "Provider Service returned 5xx");
                })
                .body(ProviderSummariesResponse.class);
        if (response == null || response.providers() == null) {
            return Map.of();
        }
        Map<UUID, ProviderSummary> result = new HashMap<>();
        for (ProviderSummaryDto p : response.providers()) {
            if (p != null && p.id() != null) {
                result.put(p.id(), new ProviderSummary(p.displayName(), p.primarySkill()));
            }
        }
        return result;
    }

    /** Response body of the Provider Service summaries endpoint. */
    record ProviderSummariesResponse(List<ProviderSummaryDto> providers) {
    }

    /** One provider in the summaries response. */
    record ProviderSummaryDto(UUID id, String displayName, String primarySkill) {
    }
}
