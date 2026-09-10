package com.homefix.catalog.support;

import java.util.List;
import java.util.Optional;

import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.cache.CatalogCachePort;

/**
 * A simple non-expiring {@link CatalogCachePort} test double that records put/hit/invalidate
 * counts, letting service tests assert read-through behaviour and that every Admin write
 * invalidates the cache (Requirement 3.8) without depending on wall-clock time.
 */
public class RecordingCatalogCache implements CatalogCachePort {

    private List<CategoryView> value;
    private int puts;
    private int hits;
    private int invalidations;

    @Override
    public Optional<List<CategoryView>> getActiveCatalog() {
        if (value != null) {
            hits++;
            return Optional.of(value);
        }
        return Optional.empty();
    }

    @Override
    public void putActiveCatalog(List<CategoryView> catalog) {
        this.value = List.copyOf(catalog);
        puts++;
    }

    @Override
    public void invalidate() {
        this.value = null;
        invalidations++;
    }

    public void resetCounters() {
        puts = 0;
        hits = 0;
        invalidations = 0;
    }

    public int puts() {
        return puts;
    }

    public int hits() {
        return hits;
    }

    public int invalidations() {
        return invalidations;
    }
}
