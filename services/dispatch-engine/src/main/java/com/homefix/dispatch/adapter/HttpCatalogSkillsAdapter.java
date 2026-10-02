package com.homefix.dispatch.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.EnrichmentUnavailableException;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.port.SubcategorySkillsPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link SubcategorySkillsPort} adapter: reads a subcategory's skill tags from the Service
 * Catalog (Requirements 3.3, 8.2).
 *
 * <p>Uses the catalog's existing {@code GET /catalog/categories} rather than a new endpoint. That
 * listing is public, served read-through from the catalog's cache, and already carries
 * {@code skillTags} on every active subcategory; the Booking Service's bookability check reads the
 * same listing. Being absent from it means the subcategory is unknown or has been deactivated
 * (Requirement 3.5), which this adapter reports as {@link UnresolvableBookingException}: retrying
 * will not bring it back, and dispatching work for a withdrawn service is what deactivation exists
 * to prevent.
 *
 * <p>Runs under the shared resilience stack (Requirement 24) with the critical-path 5 s timeout.
 * A timeout, 5xx, open breaker, or any other failure is reported as
 * {@link EnrichmentUnavailableException}, so the consumer retries and eventually dead-letters the
 * event for replay rather than guessing at the required skills.
 */
@Component
public class HttpCatalogSkillsAdapter implements SubcategorySkillsPort {

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "catalog-service";

    private static final ParameterizedTypeReference<List<CategoryView>> CATEGORY_LIST =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final ResilientCall<List<String>> resilientCall;

    // Two constructors: Spring must be told which one to use.
    @Autowired
    public HttpCatalogSkillsAdapter(DispatchClientProperties properties, ResilienceFactory resilienceFactory) {
        this(properties.getCatalogServiceBaseUrl(), resilienceFactory, TimeoutProfile.CRITICAL_PATH.timeout());
    }

    /** Explicit-timeout constructor so tests can exercise the timeout path without waiting 5 s. */
    HttpCatalogSkillsAdapter(String baseUrl, ResilienceFactory resilienceFactory, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, timeout, HttpCatalogSkillsAdapter::classifyFailure);
    }

    @Override
    public List<String> requiredSkillTags(UUID subcategoryId) {
        return resilientCall.execute(() -> {
            List<CategoryView> catalog = restClient.get()
                    .uri("/catalog/categories")
                    .retrieve()
                    // 5xx is transient: surface it so retry and the breaker act on it (Req 24.2).
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new TransientFailures.ServerErrorException(
                                res.getStatusCode().value(), "Service Catalog returned 5xx");
                    })
                    .body(CATEGORY_LIST);
            if (catalog == null) {
                throw new IllegalStateException("Service Catalog returned an empty listing");
            }
            return catalog.stream()
                    .filter(category -> category.subcategories() != null)
                    .flatMap(category -> category.subcategories().stream())
                    .filter(sub -> subcategoryId.equals(sub.id()))
                    .findFirst()
                    .map(sub -> sub.skillTags() == null ? List.<String>of() : List.copyOf(sub.skillTags()))
                    .orElseThrow(() -> new UnresolvableBookingException("subcategory " + subcategoryId
                            + " is not in the active Service Catalog (unknown or deactivated)"));
        });
    }

    /**
     * Fallback (Requirement 24.4): a subcategory confirmed absent stays permanent; every other
     * failure means the catalog was not usefully consulted and is reported as recoverable.
     */
    private static List<String> classifyFailure(Throwable cause) {
        if (cause instanceof UnresolvableBookingException permanent) {
            throw permanent;
        }
        throw new EnrichmentUnavailableException(
                "Service Catalog skill-tag lookup unavailable: " + cause, cause);
    }

    /** Active category projection returned by {@code GET /catalog/categories}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CategoryView(UUID id, List<SubcategoryView> subcategories) {
    }

    /** Active subcategory projection nested in {@link CategoryView}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SubcategoryView(UUID id, List<String> skillTags) {
    }
}
