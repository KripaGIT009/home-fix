package com.homefix.booking.address;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link CustomerAddressPort}: {@code GET /internal/addresses/{id}} on the Customer
 * Service with the shared {@code X-Internal-Api-Key}. Selected with
 * {@code homefix.customer.client=http}.
 *
 * <p>The address only decorates a booking read, so every failure — the service down, the key
 * refused, the address deleted (404) — degrades to "no address" rather than failing the read. It
 * runs under the shared resilience stack (Requirement 24) so a Customer Service outage does not
 * hold every job-detail request for the full timeout.
 */
@Component
@ConditionalOnProperty(name = "homefix.customer.client", havingValue = "http")
public class HttpCustomerAddressAdapter implements CustomerAddressPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCustomerAddressAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "customer-service";

    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final ResilientCall<Optional<ServiceAddress>> call;

    public HttpCustomerAddressAdapter(@Value("${homefix.customer.base-url}") String baseUrl,
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
                    log.warn("Customer Service unavailable; service address unresolved: {}",
                            cause.getMessage());
                    return Optional.empty();
                });
    }

    @Override
    public Optional<ServiceAddress> find(UUID addressId) {
        if (addressId == null) {
            return Optional.empty();
        }
        return call.execute(() -> {
            AddressView view = restClient.get()
                    .uri("/internal/addresses/{id}", addressId)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Customer Service returned 5xx");
                    })
                    // A deleted address is an answer, not an outage: no retry, no breaker.
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    })
                    .body(AddressView.class);
            if (view == null || view.addressId() == null) {
                return Optional.empty();
            }
            return Optional.of(new ServiceAddress(view.label(), view.lat(), view.lng()));
        });
    }

    /** Response shape of the Customer Service's internal address lookup. */
    record AddressView(UUID addressId, String label, double lat, double lng) {
    }
}
