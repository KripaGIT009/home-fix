package com.homefix.catalog.blocker;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link CategoryDependencyPort} used until the real Provider/Booking Service clients
 * are wired in. It reports no blockers so category deletion is functional in local/dev
 * environments.
 *
 * <p>A production HTTP adapter can wrap the Provider and Booking Service internal endpoints
 * behind this same port without touching the deletion business logic. Tests supply their own
 * mock, so this bean only activates when no other {@link CategoryDependencyPort} is present.
 */
@Component
public class StubCategoryDependencyAdapter implements CategoryDependencyPort {

    private static final Logger log = LoggerFactory.getLogger(StubCategoryDependencyAdapter.class);

    @Override
    public CategoryBlockers findBlockers(UUID categoryId) {
        log.debug("Stub dependency check: reporting no blockers for category {}", categoryId);
        return CategoryBlockers.none();
    }
}
