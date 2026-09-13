package com.homefix.provider.booking;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves subcategory ids to display names for the provider's active-job list.
 *
 * <p>The Booking Service carries only a {@code subcategoryId}; the name belongs to the Catalog
 * Service. The catalog is small and changes rarely, so the whole tree is fetched and held for a
 * short TTL instead of issuing one lookup per job.
 *
 * <p>Every failure degrades to "no name known" rather than propagating: a missing label should
 * never cost a provider the rest of their dashboard.
 */
@Component
public class CatalogSubcategoryNames {

    private static final Logger log = LoggerFactory.getLogger(CatalogSubcategoryNames.class);

    private static final Duration TTL = Duration.ofMinutes(5);

    private static final ParameterizedTypeReference<List<Category>> CATEGORIES =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>(Snapshot.empty());

    public CatalogSubcategoryNames(
            @Value("${homefix.catalog.base-url:http://catalog-service:8085}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** @return the subcategory's display name, or an empty string when it cannot be resolved. */
    public String nameOf(UUID subcategoryId) {
        if (subcategoryId == null) {
            return "";
        }
        return names().getOrDefault(subcategoryId, "");
    }

    private Map<UUID, String> names() {
        Snapshot current = cache.get();
        if (!current.isStale()) {
            return current.names();
        }
        try {
            List<Category> categories = restClient.get()
                    .uri("/catalog/categories")
                    .retrieve()
                    .body(CATEGORIES);
            Map<UUID, String> resolved = new HashMap<>();
            if (categories != null) {
                for (Category category : categories) {
                    if (category.subcategories() == null) {
                        continue;
                    }
                    for (Subcategory sub : category.subcategories()) {
                        if (sub.id() != null && sub.name() != null) {
                            resolved.put(sub.id(), sub.name());
                        }
                    }
                }
            }
            Snapshot fresh = new Snapshot(Map.copyOf(resolved), System.nanoTime());
            cache.set(fresh);
            return fresh.names();
        } catch (RuntimeException e) {
            // Serve the previous snapshot if we have one; a stale name beats no dashboard.
            log.warn("Catalog names unavailable; active jobs will render without service names", e);
            return current.names();
        }
    }

    private record Snapshot(Map<UUID, String> names, long fetchedAtNanos) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), 0L);
        }

        boolean isStale() {
            return fetchedAtNanos == 0L
                    || System.nanoTime() - fetchedAtNanos > TTL.toNanos();
        }
    }

    /** Minimal projections of the catalog response — only the fields needed for naming. */
    private record Category(UUID id, List<Subcategory> subcategories) {
    }

    private record Subcategory(UUID id, String name) {
    }
}
