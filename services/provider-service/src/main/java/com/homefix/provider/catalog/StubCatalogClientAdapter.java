package com.homefix.provider.catalog;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link CatalogClientPort} used until the real Service Catalog Service client is
 * wired in. It treats every well-formed category/subcategory reference as active so the
 * provider flow is functional in local/dev environments.
 *
 * <p>Selecting {@code homefix.catalog.client=http} (a future adapter) or supplying a mock in
 * tests replaces this behaviour without touching the profile business logic.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.client", havingValue = "stub", matchIfMissing = true)
public class StubCatalogClientAdapter implements CatalogClientPort {

    private static final Logger log = LoggerFactory.getLogger(StubCatalogClientAdapter.class);

    @Override
    public boolean isCategoryActive(UUID categoryId) {
        log.debug("Stub catalog: treating category {} as active", categoryId);
        return categoryId != null;
    }

    @Override
    public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
        log.debug("Stub catalog: treating subcategory {} under category {} as active",
                subcategoryId, categoryId);
        return categoryId != null && subcategoryId != null;
    }
}
