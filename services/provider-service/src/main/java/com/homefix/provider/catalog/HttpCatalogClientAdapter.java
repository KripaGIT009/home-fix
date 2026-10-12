package com.homefix.provider.catalog;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TimeoutProfile;
import com.homefix.shared.resilience.TransientFailures;

/**
 * Production {@link CatalogClientPort}: checks categories and subcategories against the Service
 * Catalog over HTTP (Requirements 4.3, 4.8, MT-1.2). Selected with
 * {@code homefix.catalog.client=http}; {@link StubCatalogClientAdapter} stays the default.
 *
 * <p>{@code GET /catalog/categories} is the catalog's public, cache-backed listing and already
 * excludes deactivated categories and subcategories, so membership in that response <em>is</em>
 * the "active" answer, the same contract booking-service's catalog adapter relies on. With this
 * adapter a Tenant cannot be created or edited with an inactive category (previously the stub
 * accepted every id), and a provider cannot select one.
 *
 * <p>The listing is small and one validation asks about several ids (a profile update can name
 * five categories with ten subcategories each), so it is fetched once and held for
 * {@link #CACHE_TTL}. A category deactivated within that window can still be accepted, as it can
 * within the catalog's own cache TTL.
 *
 * <p>Runs under the shared resilience stack (Requirement 24) with a breaker keyed on
 * {@value #DEPENDENCY}. When the catalog cannot answer, the lookup fails with
 * {@code 503 CATALOG_UNAVAILABLE} rather than answering "inactive": a catalog outage must not be
 * reported to an Admin as an invalid category, nor accepted as a valid one.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.client", havingValue = "http")
public class HttpCatalogClientAdapter implements CatalogClientPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCatalogClientAdapter.class);

    /** Downstream dependency name used for breaker keying, metrics, and the WARN log. */
    static final String DEPENDENCY = "catalog-service";

    /** How long one fetched listing answers lookups. */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<List<CategoryView>> CATEGORY_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final ResilientCall<Map<UUID, Set<UUID>>> catalogCall;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public HttpCatalogClientAdapter(
            ResilienceFactory resilienceFactory,
            @Value("${homefix.catalog.base-url:http://catalog-service:8085}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CALL_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) CALL_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.catalogCall = ResilientCall.forDependency(
                resilienceFactory, DEPENDENCY, TimeoutProfile.STANDARD,
                cause -> {
                    log.warn("Service Catalog unavailable; category checks cannot be answered: {}",
                            cause.getMessage());
                    throw new ProviderException(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE",
                            "The service catalog is unavailable; try again shortly");
                });
    }

    @Override
    public boolean isCategoryActive(UUID categoryId) {
        return categoryId != null && activeCatalog().containsKey(categoryId);
    }

    @Override
    public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
        if (categoryId == null || subcategoryId == null) {
            return false;
        }
        return activeCatalog().getOrDefault(categoryId, Set.of()).contains(subcategoryId);
    }

    /** Active category id to its active subcategory ids, from the cache or a fresh fetch. */
    private Map<UUID, Set<UUID>> activeCatalog() {
        Snapshot current = cache.get();
        if (current != null && !current.isStale()) {
            return current.catalog();
        }
        Map<UUID, Set<UUID>> fresh = catalogCall.execute(this::fetchActiveCatalog);
        cache.set(new Snapshot(fresh, System.nanoTime()));
        return fresh;
    }

    /** {@code GET /catalog/categories}: the active catalog, deactivated entries already excluded. */
    private Map<UUID, Set<UUID>> fetchActiveCatalog() {
        List<CategoryView> categories = restClient.get()
                .uri("/catalog/categories")
                .retrieve()
                // 5xx is transient: surface it so retry and the breaker act on it (Req 24.2).
                .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                    throw new TransientFailures.ServerErrorException(
                            res.getStatusCode().value(), "Service Catalog returned 5xx");
                })
                .body(CATEGORY_LIST);
        Map<UUID, Set<UUID>> active = new HashMap<>();
        if (categories == null) {
            return Map.of();
        }
        for (CategoryView category : categories) {
            if (category.id() == null) {
                continue;
            }
            Set<UUID> subcategories = category.subcategories() == null
                    ? Set.of()
                    : category.subcategories().stream()
                            .map(SubcategoryView::id)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toUnmodifiableSet());
            active.put(category.id(), subcategories);
        }
        return Map.copyOf(active);
    }

    private record Snapshot(Map<UUID, Set<UUID>> catalog, long fetchedAtNanos) {

        boolean isStale() {
            return System.nanoTime() - fetchedAtNanos > CACHE_TTL.toNanos();
        }
    }

    /** Active category projection returned by {@code GET /catalog/categories}. */
    record CategoryView(UUID id, List<SubcategoryView> subcategories) {
    }

    /** Active subcategory projection nested in {@link CategoryView}. */
    record SubcategoryView(UUID id) {
    }
}
