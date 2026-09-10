package com.homefix.catalog.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.catalog.api.dto.CategoryRequest;
import com.homefix.catalog.api.dto.CategoryResponse;
import com.homefix.catalog.api.dto.SubcategoryRequest;
import com.homefix.catalog.api.dto.SubcategoryResponse;
import com.homefix.catalog.service.CatalogService;

import jakarta.validation.Valid;

/**
 * Admin CRUD endpoints for service categories and subcategories (Requirement 3.2–3.7).
 *
 * <p>These endpoints require an authenticated ADMIN principal; role enforcement is performed by
 * the shared {@code RbacEnforcementFilter} (Task 4). Every write invalidates the read-through
 * cache in the service layer (Requirement 3.8).
 */
@RestController
@RequestMapping("/admin/catalog")
public class AdminCatalogController {

    private final CatalogService catalogService;

    public AdminCatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ---------- Categories ----------

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> listCategories() {
        return ResponseEntity.ok(catalogService.listAllCategories().stream()
                .map(CategoryResponse::from).toList());
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CategoryResponse.from(catalogService.createCategory(request)));
    }

    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable("categoryId") UUID categoryId,
                                                           @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(CategoryResponse.from(catalogService.updateCategory(categoryId, request)));
    }

    @PostMapping("/categories/{categoryId}/activate")
    public ResponseEntity<CategoryResponse> activateCategory(@PathVariable("categoryId") UUID categoryId) {
        return ResponseEntity.ok(CategoryResponse.from(catalogService.activateCategory(categoryId)));
    }

    @PostMapping("/categories/{categoryId}/deactivate")
    public ResponseEntity<CategoryResponse> deactivateCategory(@PathVariable("categoryId") UUID categoryId) {
        return ResponseEntity.ok(CategoryResponse.from(catalogService.deactivateCategory(categoryId)));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable("categoryId") UUID categoryId) {
        catalogService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
    }

    // ---------- Subcategories ----------

    @GetMapping("/categories/{categoryId}/subcategories")
    public ResponseEntity<List<SubcategoryResponse>> listSubcategories(
            @PathVariable("categoryId") UUID categoryId) {
        return ResponseEntity.ok(catalogService.listSubcategories(categoryId).stream()
                .map(SubcategoryResponse::from).toList());
    }

    @PostMapping("/categories/{categoryId}/subcategories")
    public ResponseEntity<SubcategoryResponse> createSubcategory(
            @PathVariable("categoryId") UUID categoryId,
            @Valid @RequestBody SubcategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SubcategoryResponse.from(catalogService.createSubcategory(categoryId, request)));
    }

    @PutMapping("/subcategories/{subcategoryId}")
    public ResponseEntity<SubcategoryResponse> updateSubcategory(
            @PathVariable("subcategoryId") UUID subcategoryId,
            @Valid @RequestBody SubcategoryRequest request) {
        return ResponseEntity.ok(
                SubcategoryResponse.from(catalogService.updateSubcategory(subcategoryId, request)));
    }

    @PostMapping("/subcategories/{subcategoryId}/activate")
    public ResponseEntity<SubcategoryResponse> activateSubcategory(
            @PathVariable("subcategoryId") UUID subcategoryId) {
        return ResponseEntity.ok(
                SubcategoryResponse.from(catalogService.activateSubcategory(subcategoryId)));
    }

    @PostMapping("/subcategories/{subcategoryId}/deactivate")
    public ResponseEntity<SubcategoryResponse> deactivateSubcategory(
            @PathVariable("subcategoryId") UUID subcategoryId) {
        return ResponseEntity.ok(
                SubcategoryResponse.from(catalogService.deactivateSubcategory(subcategoryId)));
    }

    @DeleteMapping("/subcategories/{subcategoryId}")
    public ResponseEntity<Void> deleteSubcategory(@PathVariable("subcategoryId") UUID subcategoryId) {
        catalogService.deleteSubcategory(subcategoryId);
        return ResponseEntity.noContent().build();
    }
}
