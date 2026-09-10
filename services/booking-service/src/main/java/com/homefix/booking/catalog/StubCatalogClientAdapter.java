package com.homefix.booking.catalog;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development {@link CatalogClientPort} that treats every well-formed category/subcategory
 * reference as active, so the booking flow is functional without a Service Catalog to talk to.
 *
 * <p>Selecting {@code homefix.catalog.client=http} swaps in {@link HttpCatalogClientAdapter},
 * which asks the real Service Catalog; supplying a mock in tests replaces either without
 * touching the booking business logic.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.client", havingValue = "stub", matchIfMissing = true)
public class StubCatalogClientAdapter implements CatalogClientPort {

    private static final Logger log = LoggerFactory.getLogger(StubCatalogClientAdapter.class);

    @Override
    public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
        log.debug("Stub catalog: treating subcategory {} under category {} as active",
                subcategoryId, categoryId);
        return categoryId != null && subcategoryId != null;
    }
}
