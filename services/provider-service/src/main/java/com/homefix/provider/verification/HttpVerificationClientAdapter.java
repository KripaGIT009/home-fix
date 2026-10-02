package com.homefix.provider.verification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.TransientFailures;

/**
 * {@link VerificationClientPort} adapter that asks the Verification Service which candidate
 * providers are {@code APPROVED}, through its internal batch endpoint
 * {@code POST /internal/verifications/approved}.
 *
 * <p>One call per dispatch search, not one per candidate: the per-provider
 * {@code GET /verifications/{id}/job-assignment-eligibility} gate would cost a round trip for
 * every provider in the radius, on the emergency path. Ids are sent in chunks of
 * {@value #MAX_IDS_PER_REQUEST}, the Verification Service's per-request cap.
 *
 * <p>The search is on the dispatch critical path, so the call runs under the shared resilience
 * stack with the {@link TimeoutProfile#CRITICAL_PATH} budget (Requirement 24.3) — the same value
 * also bounds the socket connect and read, so a hung connection cannot outlive it — with retry for
 * transient failures (24.2) and a circuit breaker keyed on {@value #DEPENDENCY} (24.1).
 *
 * <p><strong>Fails closed.</strong> When the Verification Service is unreachable, the breaker is
 * open, or it refuses the credential, the adapter reports <em>no</em> approved providers and logs
 * a WARN naming the dependency (24.4). An unverified provider must never be dispatched because a
 * dependency was down; the cost is that a search during the outage finds nobody, and the Dispatch
 * Engine's own retry/expansion handles that like any other empty search.
 *
 * <p>Unlike the opt-in Booking adapter, this one is always active, so a missing credential is not
 * a startup failure: the eligibility endpoint is refused without the same key anyway, and the rest
 * of the Provider Service (profiles, wallet) should not go down over it. It is logged as an ERROR
 * at startup and every lookup fails closed.
 *
 * <h2>Admin provider list ({@link VerificationAdminClientPort}, Requirement 19.2)</h2>
 * <p>The same client, credential and circuit breaker also serve the Admin screen, under different
 * failure policies:
 * <ul>
 *   <li>{@link #statusesOf} reads every listed provider's status in one batch call
 *       ({@code POST /internal/verifications/statuses}) and <em>fails soft</em>: when the service
 *       cannot answer the result is empty and the list shows the status as unknown.</li>
 *   <li>{@link #suspend} / {@link #reinstate} are writes. They are not retried — a retry after a
 *       suspension that did land but timed out would answer 409 and mask the success — and every
 *       failure is surfaced: the Verification Service's own 4xx (e.g. 409
 *       {@code INVALID_STATE_TRANSITION}) passes through with its error code, and an unreachable
 *       service, 5xx or refused credential becomes 503 {@code VERIFICATION_UNAVAILABLE}. The socket
 *       timeouts still bound them.</li>
 * </ul>
 */
@Component
public class HttpVerificationClientAdapter implements VerificationClientPort, VerificationAdminClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpVerificationClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "verification-service";

    /** Header carrying the shared service credential the Verification Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    /** Matches the Verification Service's cap on ids per batch request. */
    static final int MAX_IDS_PER_REQUEST = 500;

    private final RestClient restClient;
    private final ResilientCall<Set<UUID>> resilientCall;
    private final ResilientCall<Optional<Map<UUID, String>>> statusesCall;
    private final boolean configured;

    @Autowired
    public HttpVerificationClientAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.verification.base-url:http://verification-service:8094}") String baseUrl,
            @Value("${homefix.verification.internal-api-key:}") String internalApiKey) {
        this(resilienceFactory, baseUrl, internalApiKey, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpVerificationClientAdapter(ResilienceFactory resilienceFactory, String baseUrl,
                                  String internalApiKey, Duration timeout) {
        this.configured = internalApiKey != null && !internalApiKey.isBlank();
        if (!configured) {
            log.error("homefix.verification.internal-api-key is not configured; no provider can be "
                    + "confirmed APPROVED, so every dispatch eligibility search will return nobody");
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
                    log.warn("Verification Service unavailable ({}); treating no candidate as APPROVED "
                            + "(fail closed)", DEPENDENCY, cause);
                    return Set.of();
                });
        this.statusesCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, timeout,
                cause -> {
                    log.warn("Verification Service unavailable ({}); showing provider verification "
                            + "statuses as unknown", DEPENDENCY, cause);
                    return Optional.empty();
                });
    }

    @Override
    public Set<UUID> approvedAmong(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty() || !configured) {
            return Set.of();
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(providerIds));
        Set<UUID> approved = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_REQUEST) {
            List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_REQUEST, ids.size()));
            approved.addAll(resilientCall.execute(() -> fetchApproved(chunk)));
        }
        // Never trust the response to stay within the question: only ids we asked about count.
        approved.retainAll(ids);
        return approved;
    }

    private Set<UUID> fetchApproved(List<UUID> chunk) {
        ApprovedProvidersResponse response = restClient.post()
                .uri("/internal/verifications/approved")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApprovedProvidersRequest(List.copyOf(chunk)))
                .retrieve()
                // 5xx is a transient failure: surface it so retry/breaker act on it (Req 24.2).
                .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                    throw new TransientFailures.ServerErrorException(
                            res.getStatusCode().value(), "Verification Service returned 5xx");
                })
                .body(ApprovedProvidersResponse.class);
        if (response == null || response.approvedProviderIds() == null) {
            return Set.of();
        }
        return new HashSet<>(response.approvedProviderIds());
    }

    /** Request body of the Verification Service batch endpoint. */
    record ApprovedProvidersRequest(List<UUID> providerIds) {
    }

    /** Response body of the Verification Service batch endpoint. */
    record ApprovedProvidersResponse(List<UUID> approvedProviderIds) {
    }

    // ============================= Admin provider list (Req 19.2) =================

    @Override
    public Optional<Map<UUID, String>> statusesOf(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        if (!configured) {
            return Optional.empty();
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(providerIds));
        Map<UUID, String> statuses = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_REQUEST) {
            List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_REQUEST, ids.size()));
            Optional<Map<UUID, String>> part = statusesCall.execute(() -> fetchStatuses(chunk));
            if (part.isEmpty()) {
                return Optional.empty();
            }
            statuses.putAll(part.get());
        }
        // Only ids we asked about count.
        statuses.keySet().retainAll(ids);
        return Optional.of(statuses);
    }

    private Optional<Map<UUID, String>> fetchStatuses(List<UUID> chunk) {
        StatusesResponse response = restClient.post()
                .uri("/internal/verifications/statuses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApprovedProvidersRequest(List.copyOf(chunk)))
                .retrieve()
                .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                    throw new TransientFailures.ServerErrorException(
                            res.getStatusCode().value(), "Verification Service returned 5xx");
                })
                .body(StatusesResponse.class);
        if (response == null || response.statuses() == null) {
            return Optional.of(Map.of());
        }
        return Optional.of(new HashMap<>(response.statuses()));
    }

    @Override
    public String suspend(UUID providerId, UUID actorId, String reason) {
        return changeStatus(providerId, "suspend", actorId, reason);
    }

    @Override
    public String reinstate(UUID providerId, UUID actorId, String reason) {
        return changeStatus(providerId, "reinstate", actorId, reason);
    }

    private String changeStatus(UUID providerId, String action, UUID actorId, String reason) {
        if (!configured) {
            throw unavailable();
        }
        StatusChangeResponse response;
        try {
            response = restClient.post()
                    .uri("/internal/verifications/{providerId}/{action}", providerId, action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new StatusChangeRequest(actorId, reason))
                    .retrieve()
                    .body(StatusChangeResponse.class);
        } catch (RestClientResponseException e) {
            throw translate(e, providerId, action);
        } catch (RestClientException e) {
            log.warn("Verification Service unavailable ({}) for {} of provider {}", DEPENDENCY, action,
                    providerId, e);
            throw unavailable();
        }
        if (response == null || response.status() == null) {
            throw unavailable();
        }
        return response.status();
    }

    /**
     * The Verification Service's own refusals pass through with their status and error code, so
     * the Admin sees e.g. 409 {@code INVALID_STATE_TRANSITION} for suspending a provider who is not
     * approved. A missing verification record (404 there) is a conflict here — the provider exists,
     * there is just nothing to suspend or reinstate. A refused credential and 5xx are this side's
     * or the dependency's fault, not the Admin's: 503.
     */
    private ProviderException translate(RestClientResponseException e, UUID providerId, String action) {
        int code = e.getStatusCode().value();
        if (code == HttpStatus.NOT_FOUND.value()) {
            return new ProviderException(HttpStatus.CONFLICT, "VERIFICATION_NOT_FOUND",
                    "Provider " + providerId + " has no verification record, so there is nothing to " + action);
        }
        if (e.getStatusCode().is4xxClientError() && code != HttpStatus.UNAUTHORIZED.value()
                && code != HttpStatus.FORBIDDEN.value()) {
            DownstreamError error = null;
            try {
                error = e.getResponseBodyAs(DownstreamError.class);
            } catch (RuntimeException ignored) {
                // Unparseable body: fall back to a generic code below.
            }
            String errorCode = error != null && error.errorCode() != null ? error.errorCode() : "VERIFICATION_REJECTED";
            String message = error != null && error.message() != null
                    ? error.message() : "Verification Service refused to " + action + " the provider";
            return new ProviderException(HttpStatus.valueOf(code), errorCode, message);
        }
        log.warn("Verification Service refused {} of provider {} with HTTP {} ({})", action, providerId,
                code, DEPENDENCY);
        return unavailable();
    }

    private static ProviderException unavailable() {
        return new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_UNAVAILABLE",
                "The Verification Service is unavailable; the provider's status was not changed");
    }

    /** Response body of {@code POST /internal/verifications/statuses}. */
    record StatusesResponse(Map<UUID, String> statuses) {
    }

    /** Request body of the internal suspend / reinstate actions: the acting Admin and a reason. */
    record StatusChangeRequest(UUID actorId, String reason) {
    }

    /** Response body of the internal suspend / reinstate actions. */
    record StatusChangeResponse(UUID providerId, String status) {
    }

    /** The shared error envelope, as far as this adapter reads it. */
    record DownstreamError(String errorCode, String message) {
    }
}
