package com.homefix.catalog.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.catalog.api.dto.CategoryRequest;
import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.api.dto.SubcategoryRequest;
import com.homefix.catalog.api.dto.SubcategoryView;
import com.homefix.catalog.blocker.CategoryBlockers;
import com.homefix.catalog.blocker.CategoryDependencyPort;
import com.homefix.catalog.cache.CatalogCachePort;
import com.homefix.catalog.config.CatalogProperties;
import com.homefix.catalog.domain.ServiceCategory;
import com.homefix.catalog.domain.ServiceCategoryRepository;
import com.homefix.catalog.domain.ServiceSubcategory;
import com.homefix.catalog.domain.ServiceSubcategoryRepository;

/**
 * Core catalog business logic: Admin CRUD for categories and subcategories, customer-facing
 * read-through cached listings, and category-deletion blocker enforcement (Requirement 3).
 *
 * <p>External dependencies are expressed as ports ({@link CatalogCachePort},
 * {@link CategoryDependencyPort}) so the logic is fully unit-testable. Every Admin write
 * invalidates the cache so subsequent reads reflect the new database state within the cache
 * TTL (Requirement 3.8).
 */
@Service
public class CatalogService {

    private final ServiceCategoryRepository categoryRepository;
    private final ServiceSubcategoryRepository subcategoryRepository;
    private final CatalogCachePort cache;
    private final CategoryDependencyPort dependencyPort;
    private final CatalogProperties props;

    public CatalogService(ServiceCategoryRepository categoryRepository,
                          ServiceSubcategoryRepository subcategoryRepository,
                          CatalogCachePort cache,
                          CategoryDependencyPort dependencyPort,
                          CatalogProperties props) {
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.cache = cache;
        this.dependencyPort = dependencyPort;
        this.props = props;
    }

    // ================= Category CRUD (Requirement 3.1, 3.2) =================

    @Transactional
    public ServiceCategory createCategory(CategoryRequest request) {
        validateCategoryName(request.name());
        ServiceCategory category = ServiceCategory.create(
                request.name().trim(), request.description(), request.iconUrl(), request.displayOrder());
        ServiceCategory saved = categoryRepository.save(category);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public ServiceCategory updateCategory(UUID categoryId, CategoryRequest request) {
        validateCategoryName(request.name());
        ServiceCategory category = getCategory(categoryId);
        category.update(request.name().trim(), request.description(), request.iconUrl(), request.displayOrder());
        ServiceCategory saved = categoryRepository.save(category);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public ServiceCategory activateCategory(UUID categoryId) {
        ServiceCategory category = getCategory(categoryId);
        category.activate();
        ServiceCategory saved = categoryRepository.save(category);
        cache.invalidate();
        return saved;
    }

    /**
     * Deactivates a category (Requirement 3.5): it is excluded from customer-facing listings
     * and new bookings for its subcategories are blocked; existing active bookings are
     * unaffected. Cache is invalidated so the change is visible within the TTL bound.
     */
    @Transactional
    public ServiceCategory deactivateCategory(UUID categoryId) {
        ServiceCategory category = getCategory(categoryId);
        category.deactivate();
        ServiceCategory saved = categoryRepository.save(category);
        cache.invalidate();
        return saved;
    }

    /**
     * Deletes a category, rejecting the deletion if any active providers or active bookings
     * reference it and returning their counts and identifiers (Requirement 3.6).
     */
    @Transactional
    public void deleteCategory(UUID categoryId) {
        ServiceCategory category = getCategory(categoryId);
        CategoryBlockers blockers = dependencyPort.findBlockers(categoryId);
        if (blockers.hasBlockers()) {
            List<String> details = new ArrayList<>();
            details.add("activeProviderCount=" + blockers.providerCount());
            blockers.providerIds().forEach(id -> details.add("provider=" + id));
            details.add("activeBookingCount=" + blockers.bookingCount());
            blockers.bookingReferences().forEach(ref -> details.add("booking=" + ref));
            throw new CatalogException(HttpStatus.CONFLICT, "CATEGORY_HAS_ACTIVE_DEPENDENCIES",
                    "Cannot delete category " + categoryId + ": it has "
                            + blockers.providerCount() + " active provider(s) and "
                            + blockers.bookingCount() + " active booking(s)",
                    details);
        }
        subcategoryRepository.deleteAll(subcategoryRepository.findByCategoryId(categoryId));
        categoryRepository.delete(category);
        cache.invalidate();
    }

    private void validateCategoryName(String name) {
        if (name == null || name.isBlank()) {
            throw CatalogException.validation("Category name is required");
        }
    }

    // ================= Subcategory CRUD (Requirement 3.3, 3.4, 3.7) =================

    /**
     * Creates a subcategory under an existing, active parent category. Rejects creation under a
     * non-existent or deactivated parent, identifying the invalid parent (Requirement 3.4).
     */
    @Transactional
    public ServiceSubcategory createSubcategory(UUID categoryId, SubcategoryRequest request) {
        ServiceCategory parent = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CatalogException(HttpStatus.BAD_REQUEST, "PARENT_CATEGORY_NOT_FOUND",
                        "Cannot create subcategory: parent category " + categoryId + " does not exist"));
        if (!parent.isActive()) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "PARENT_CATEGORY_DEACTIVATED",
                    "Cannot create subcategory: parent category " + categoryId + " is deactivated");
        }
        validateSubcategory(request);
        ServiceSubcategory subcategory = ServiceSubcategory.create(
                categoryId,
                request.name().trim(),
                request.basePrice(),
                request.estimatedDurationMin(),
                normalizeTags(request.skillTags()),
                request.emergencyAvailable());
        ServiceSubcategory saved = subcategoryRepository.save(subcategory);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public ServiceSubcategory updateSubcategory(UUID subcategoryId, SubcategoryRequest request) {
        ServiceSubcategory subcategory = getSubcategory(subcategoryId);
        validateSubcategory(request);
        subcategory.update(
                request.name().trim(),
                request.basePrice(),
                request.estimatedDurationMin(),
                normalizeTags(request.skillTags()),
                request.emergencyAvailable());
        ServiceSubcategory saved = subcategoryRepository.save(subcategory);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public ServiceSubcategory activateSubcategory(UUID subcategoryId) {
        ServiceSubcategory subcategory = getSubcategory(subcategoryId);
        subcategory.activate();
        ServiceSubcategory saved = subcategoryRepository.save(subcategory);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public ServiceSubcategory deactivateSubcategory(UUID subcategoryId) {
        ServiceSubcategory subcategory = getSubcategory(subcategoryId);
        subcategory.deactivate();
        ServiceSubcategory saved = subcategoryRepository.save(subcategory);
        cache.invalidate();
        return saved;
    }

    @Transactional
    public void deleteSubcategory(UUID subcategoryId) {
        ServiceSubcategory subcategory = getSubcategory(subcategoryId);
        subcategoryRepository.delete(subcategory);
        cache.invalidate();
    }

    /** Requirement 3.7: validate base price, duration, and skill-tag count against config bounds. */
    private void validateSubcategory(SubcategoryRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw CatalogException.validation("Subcategory name is required");
        }
        BigDecimal price = request.basePrice();
        if (price == null
                || price.compareTo(props.getMinBasePrice()) < 0
                || price.compareTo(props.getMaxBasePrice()) > 0) {
            throw CatalogException.validation(
                    "Base price must be between " + props.getMinBasePrice() + " and "
                            + props.getMaxBasePrice() + " (got " + price + ")");
        }
        int duration = request.estimatedDurationMin();
        if (duration < props.getMinDurationMinutes() || duration > props.getMaxDurationMinutes()) {
            throw CatalogException.validation(
                    "Estimated duration must be between " + props.getMinDurationMinutes() + " and "
                            + props.getMaxDurationMinutes() + " minutes (got " + duration + ")");
        }
        int tagCount = request.skillTags() == null ? 0 : request.skillTags().size();
        if (tagCount > props.getMaxSkillTags()) {
            throw CatalogException.validation(
                    "A subcategory may have at most " + props.getMaxSkillTags()
                            + " skill tags (got " + tagCount + ")");
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags == null ? List.of() : List.copyOf(tags);
    }

    // ================= Customer-facing cached listing (Requirement 3.5, 3.8) =================

    /**
     * Returns the active catalog (active categories with their active subcategories) served
     * read-through from the cache. On a cache miss the listing is built from the database and
     * cached with the configured TTL so subsequent reads are served fresh within the bound of
     * Requirement 3.8.
     */
    @Transactional(readOnly = true)
    public List<CategoryView> getActiveCatalog() {
        return cache.getActiveCatalog().orElseGet(() -> {
            List<CategoryView> fresh = buildActiveCatalog();
            cache.putActiveCatalog(fresh);
            return fresh;
        });
    }

    private List<CategoryView> buildActiveCatalog() {
        List<CategoryView> views = new ArrayList<>();
        for (ServiceCategory category : categoryRepository.findByActiveTrueOrderByDisplayOrderAsc()) {
            List<SubcategoryView> subs = subcategoryRepository
                    .findByCategoryIdAndActiveTrue(category.getId()).stream()
                    .map(SubcategoryView::from)
                    .toList();
            views.add(CategoryView.from(category, subs));
        }
        return views;
    }

    // ================= Admin reads =================

    @Transactional(readOnly = true)
    public List<ServiceCategory> listAllCategories() {
        return categoryRepository.findAllByOrderByDisplayOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<ServiceSubcategory> listSubcategories(UUID categoryId) {
        getCategory(categoryId);
        return subcategoryRepository.findByCategoryId(categoryId);
    }

    @Transactional(readOnly = true)
    public ServiceCategory getCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> CatalogException.categoryNotFound("Category " + categoryId + " not found"));
    }

    @Transactional(readOnly = true)
    public ServiceSubcategory getSubcategory(UUID subcategoryId) {
        return subcategoryRepository.findById(subcategoryId)
                .orElseThrow(() -> CatalogException.subcategoryNotFound(
                        "Subcategory " + subcategoryId + " not found"));
    }
}
