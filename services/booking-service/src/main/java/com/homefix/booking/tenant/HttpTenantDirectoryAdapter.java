package com.homefix.booking.tenant;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link TenantDirectoryPort}: the Provider Service's {@code /internal/tenants/**}
 * endpoints with the shared {@code X-Internal-Api-Key} (Requirement MT-10.5). Active unless
 * {@code homefix.booking.tenant-directory-client=stub}.
 *
 * <p>Built like {@code HttpCustomerAddressAdapter}: short connect/read timeouts and the shared
 * resilience stack (Requirement 24), so a Provider Service outage trips one breaker instead of
 * holding every fallback and Tenant request for the full timeout. Two differences, both deliberate:
 * <ul>
 *   <li>A 404 is an answer ("not a member", "no Tenant") and comes back empty without a retry or a
 *       breaker failure, as for a deleted address.</li>
 *   <li>A failure does <em>not</em> degrade to an empty answer. "No Tenant covers this" would hide
 *       the outage from the log that Requirement MT-4.4 asks for, and "not an admin" would turn an
 *       outage into a misleading 404, so a failed call surfaces as
 *       {@link TenantDirectoryUnavailableException} and each caller picks its own fallback (see the
 *       port).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "homefix.booking.tenant-directory-client", havingValue = "http",
        matchIfMissing = true)
public class HttpTenantDirectoryAdapter implements TenantDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(HttpTenantDirectoryAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "provider-service";

    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    /**
     * Per-request ceiling. The fallback runs inside the Dispatch Engine's searching-failed call,
     * which has its own 5 s budget; 2 s keeps one slow attempt from consuming all of it.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final ResilientCall<Object> call;

    public HttpTenantDirectoryAdapter(@Value("${homefix.booking.provider-service-url}") String baseUrl,
                                      @Value("${homefix.booking.internal-api-key}") String internalApiKey,
                                      ResilienceFactory resilienceFactory) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CALL_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) CALL_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                .requestFactory(requestFactory)
                .build();
        this.call = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.CRITICAL_PATH,
                cause -> {
                    log.warn("Provider Service unavailable; Tenant lookup failed: {}", cause.getMessage());
                    throw new TenantDirectoryUnavailableException(
                            "Tenant directory unavailable: " + cause.getMessage(), cause);
                });
    }

    @Override
    public List<CoveringTenant> covering(double latitude, double longitude, UUID categoryId) {
        Optional<CoveringView> view = fetch(CoveringView.class,
                "/internal/tenants/covering?lat={lat}&lon={lon}&categoryId={categoryId}",
                latitude, longitude, categoryId);
        if (view.isEmpty() || view.get().tenants() == null) {
            return List.of();
        }
        return view.get().tenants().stream()
                .filter(t -> t.tenantId() != null)
                .map(t -> new CoveringTenant(t.tenantId(), t.name(), t.distanceKm()))
                .toList();
    }

    @Override
    public Optional<TenantSummary> byAdmin(UUID userId) {
        return fetch(TenantView.class, "/internal/tenants/by-admin/{userId}", userId)
                .filter(v -> v.tenantId() != null)
                .map(TenantView::toSummary);
    }

    @Override
    public Optional<TenantMembership> membership(UUID tenantId, UUID providerId) {
        return fetch(MembershipView.class, "/internal/tenants/{tenantId}/providers/{providerId}",
                tenantId, providerId)
                .map(v -> new TenantMembership(v.member(), v.assignable(), v.verificationStatus()));
    }

    @Override
    public Optional<TenantRef> ofProvider(UUID providerId) {
        return fetch(TenantView.class, "/internal/tenants/of-provider/{providerId}", providerId)
                .filter(v -> v.tenantId() != null)
                .map(v -> new TenantRef(v.tenantId(), v.name()));
    }

    @Override
    public Optional<TenantSummary> byId(UUID tenantId) {
        return fetch(TenantView.class, "/internal/tenants/{tenantId}", tenantId)
                .filter(v -> v.tenantId() != null)
                .map(TenantView::toSummary);
    }

    /**
     * GETs {@code uri} under the resilience stack: the body on 2xx, empty on 404, a transient
     * failure (retried, counted by the breaker) on 5xx, and a non-transient failure on any other
     * status, since a refused internal key will not fix itself on retry.
     */
    private <T> Optional<T> fetch(Class<T> type, String uri, Object... variables) {
        Callable<Object> action = () -> restClient.get()
                .uri(uri, variables)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == HttpStatus.NOT_FOUND.value()) {
                        return Optional.empty();
                    }
                    if (response.getStatusCode().is5xxServerError()) {
                        throw new TransientFailures.ServerErrorException(status,
                                "Provider Service returned " + status);
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Provider Service answered " + status);
                    }
                    return Optional.ofNullable(response.bodyTo(type));
                });
        @SuppressWarnings("unchecked")
        Optional<T> result = (Optional<T>) call.execute(action);
        return result;
    }

    /** {@code covering}'s response shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CoveringView(List<CoveringItem> tenants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CoveringItem(UUID tenantId, String name, double distanceKm) {
    }

    /** Shape shared by {@code by-admin}, {@code of-provider} and {@code /{tenantId}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TenantView(UUID tenantId, String name, String status) {

        TenantSummary toSummary() {
            return new TenantSummary(tenantId, name, status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MembershipView(boolean member, boolean assignable, String verificationStatus) {
    }
}
