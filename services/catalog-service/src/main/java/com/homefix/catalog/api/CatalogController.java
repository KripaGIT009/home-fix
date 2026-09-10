package com.homefix.catalog.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.service.CatalogService;

/**
 * Customer-facing, read-only catalog listing (Requirement 3.5, 3.8).
 *
 * <p>Returns only active categories with their active subcategories, served read-through from
 * the cache. Deactivated categories are absent from this response (Requirement 3.5), and served
 * data reflects the database state as of no more than the cache TTL ago (Requirement 3.8).
 */
@RestController
@RequestMapping("/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** {@code GET /catalog/categories} — active catalog listing for customers. */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryView>> activeCatalog() {
        return ResponseEntity.ok(catalogService.getActiveCatalog());
    }
}
