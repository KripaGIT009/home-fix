package com.homefix.booking.catalog;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link CatalogClientPort}: checks bookability against the Service Catalog over
 * HTTP (Requirement 7.6). Selected with {@code homefix.catalog.client=http}.
 *
 * <p>{@code GET /catalog/categories} is the catalog's public, cache-backed listing and already
 * excludes deactivated categories and subcategories, so membership in that response <em>is</em>
 * the bookability answer.
 *
 * <p>Runs under the shared resilience stack (Requirement 24). The fallback answers "not
 * bookable", which the booking flow reports as service-unavailable: with the catalog down there
 * is no way to tell an active service from a deactivated one, and accepting the booking risks
 * dispatching work for a service that has been withdrawn.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.client", havingValue = "http")
public class HttpCatalogClientAdapter implements CatalogClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCatalogClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "catalog-service";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final ResilientCall<Boolean> resilientCall;

    public HttpCatalogClientAdapter(@Value("${homefix.catalog.base-url}") String baseUrl,
                                    ResilienceFactory resilienceFactory) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CALL_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) CALL_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.resilientCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.CRITICAL_PATH,
                cause -> {
                    log.warn("Service Catalog unavailable; treating the subcategory as not "
                            + "bookable: {}", cause.getMessage());
                    return Boolean.FALSE;
                });
    }

    @Override
    public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
        if (categoryId == null || subcategoryId == null) {
            return false;
        }
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
                return false;
            }
            return catalog.stream()
                    .filter(category -> categoryId.equals(category.id()))
                    .anyMatch(category -> category.subcategories() != null
                            && category.subcategories().stream()
                                    .anyMatch(sub -> subcategoryId.equals(sub.id())));
        });
    }

    private static final org.springframework.core.ParameterizedTypeReference<List<CategoryView>>
            CATEGORY_LIST = new org.springframework.core.ParameterizedTypeReference<>() {
            };

    /** Active category projection returned by {@code GET /catalog/categories}. */
    record CategoryView(UUID id, List<SubcategoryView> subcategories) {
    }

    /** Active subcategory projection nested in {@link CategoryView}. */
    record SubcategoryView(UUID id) {
    }
}
