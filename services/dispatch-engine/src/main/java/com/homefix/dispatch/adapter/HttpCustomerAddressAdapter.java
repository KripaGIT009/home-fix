package com.homefix.dispatch.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.EnrichmentUnavailableException;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.port.CustomerAddressPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link CustomerAddressPort} adapter: resolves a booking's address through the Customer
 * Service's {@code GET /internal/addresses/{addressId}} (Requirement 8.2).
 *
 * <p>The endpoint is service-to-service only, so every call presents the shared credential in
 * {@code X-Internal-Api-Key}, the same {@code INTERNAL_API_KEY} this service already presents to
 * the Booking Service.
 *
 * <p>The lookup is on the critical dispatch path, so it runs under the shared resilience stack
 * (Requirement 24): a 5 s per-call timeout (24.3), retry with exponential backoff on transient
 * failures (24.2), and a circuit breaker (24.1). Outcomes are classified so the consumer can act on
 * them:
 * <ul>
 *   <li>200 — the address's owner and coordinates.</li>
 *   <li>404 carrying {@code ADDRESS_NOT_FOUND} — the Customer Service confirms the address does not
 *       exist: {@link UnresolvableBookingException}, never retried by the resilience stack.</li>
 *   <li>Anything else — a timeout, a 5xx, an open breaker, a refused credential, or a 404 without
 *       that error code (a wrong base URL, or a Customer Service build without the endpoint):
 *       {@link EnrichmentUnavailableException}, which the consumer retries and eventually
 *       dead-letters for replay. A bare 404 is deliberately not treated as "no such address", or a
 *       misrouted deployment would permanently refuse every booking.</li>
 * </ul>
 */
@Component
public class HttpCustomerAddressAdapter implements CustomerAddressPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCustomerAddressAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "customer-service";

    /** Header carrying the shared service credential the Customer Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    /** Error code the Customer Service returns when the address genuinely does not exist. */
    static final String ADDRESS_NOT_FOUND = "ADDRESS_NOT_FOUND";

    private static final ParameterizedTypeReference<Map<String, Object>> ERROR_BODY =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final ResilientCall<ResolvedAddress> resilientCall;

    // Two constructors: Spring must be told which one to use.
    @Autowired
    public HttpCustomerAddressAdapter(DispatchClientProperties properties,
                                      ResilienceFactory resilienceFactory,
                                      @Value("${homefix.dispatch.internal-api-key:}") String internalApiKey) {
        this(properties.getCustomerServiceBaseUrl(), resilienceFactory, internalApiKey,
                TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpCustomerAddressAdapter(String baseUrl, ResilienceFactory resilienceFactory,
                               String internalApiKey, Duration timeout) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // Fail at startup rather than on the first booking: without the credential every
            // lookup is refused and every booking would be dead-lettered.
            throw new IllegalStateException(
                    "homefix.dispatch.internal-api-key is not configured; the Dispatch Engine cannot "
                            + "resolve booking addresses through the Customer Service without it");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                .requestFactory(requestFactory)
                .build();
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, timeout, HttpCustomerAddressAdapter::classifyFailure);
    }

    @Override
    public ResolvedAddress lookup(UUID addressId) {
        return resilientCall.execute(() -> restClient.get()
                .uri("/internal/addresses/{addressId}", addressId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (response.getStatusCode().is2xxSuccessful()) {
                        AddressBody body = response.bodyTo(AddressBody.class);
                        if (body == null || body.lat() == null || body.lng() == null) {
                            throw new IllegalStateException(
                                    "Customer Service returned no coordinates for address " + addressId);
                        }
                        return new ResolvedAddress(addressId, body.customerId(), body.lat(), body.lng());
                    }
                    if (status == 404 && isAddressNotFound(response.bodyTo(ERROR_BODY))) {
                        throw new UnresolvableBookingException("address " + addressId
                                + " does not exist in the Customer Service (ADDRESS_NOT_FOUND)");
                    }
                    if (response.getStatusCode().is5xxServerError()) {
                        // Transient: surface it so retry and the breaker act on it (Req 24.2).
                        throw new TransientFailures.ServerErrorException(
                                status, "Customer Service returned " + status);
                    }
                    if (status == 401 || status == 403) {
                        log.error("Customer Service refused the address lookup with HTTP {}; check that "
                                + "INTERNAL_API_KEY matches across services", status);
                    }
                    throw new IllegalStateException(
                            "Customer Service address lookup returned HTTP " + status);
                }));
    }

    /**
     * Fallback (Requirement 24.4): a confirmed missing address stays permanent; every other failure
     * means the Customer Service was not usefully consulted and is reported as recoverable.
     */
    private static ResolvedAddress classifyFailure(Throwable cause) {
        if (cause instanceof UnresolvableBookingException permanent) {
            throw permanent;
        }
        throw new EnrichmentUnavailableException(
                "Customer Service address lookup unavailable: " + cause, cause);
    }

    private static boolean isAddressNotFound(Map<String, Object> body) {
        return body != null && ADDRESS_NOT_FOUND.equals(body.get("errorCode"));
    }

    /** The subset of the Customer Service response this service reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddressBody(UUID addressId, UUID customerId, Double lat, Double lng) {
    }
}
