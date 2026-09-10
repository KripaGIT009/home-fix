package com.homefix.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.catalog.api.dto.CategoryRequest;
import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.api.dto.SubcategoryRequest;
import com.homefix.catalog.blocker.CategoryBlockers;
import com.homefix.catalog.blocker.CategoryDependencyPort;
import com.homefix.catalog.config.CatalogProperties;
import com.homefix.catalog.domain.ServiceCategory;
import com.homefix.catalog.domain.ServiceSubcategory;
import com.homefix.catalog.support.InMemoryServiceCategoryRepository;
import com.homefix.catalog.support.InMemoryServiceSubcategoryRepository;
import com.homefix.catalog.support.RecordingCatalogCache;

/**
 * Unit tests for the catalog domain logic (Requirement 3).
 *
 * <p>Tests operate against in-memory fakes and a mock dependency port — no Spring context,
 * database, or network access required.
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private InMemoryServiceCategoryRepository categoryRepository;
    private InMemoryServiceSubcategoryRepository subcategoryRepository;
    private RecordingCatalogCache cache;
    private CatalogProperties props;

    @Mock
    private CategoryDependencyPort dependencyPort;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        categoryRepository = new InMemoryServiceCategoryRepository();
        subcategoryRepository = new InMemoryServiceSubcategoryRepository();
        cache = new RecordingCatalogCache();
        props = new CatalogProperties();
        service = new CatalogService(categoryRepository, subcategoryRepository, cache, dependencyPort, props);
    }

    private ServiceCategory seedCategory(String name, int order, boolean active) {
        ServiceCategory c = ServiceCategory.create(name, "desc", "icon", order);
        if (!active) {
            c.deactivate();
        }
        return categoryRepository.save(c);
    }

    private SubcategoryRequest validSub(String name) {
        return new SubcategoryRequest(name, new BigDecimal("49.99"), 60, List.of("plumbing"), false);
    }

    // ===================== Category activation / deactivation =====================

    @Nested
    class CategoryActivation {

        @Test
        void createdCategoryIsActiveAndAppearsInCustomerListing() {
            service.createCategory(new CategoryRequest("Plumbing", "d", "i", 1));

            List<CategoryView> customerView = service.getActiveCatalog();
            assertThat(customerView).extracting(CategoryView::name).containsExactly("Plumbing");
        }

        @Test
        void deactivatedCategoryIsExcludedFromCustomerListing() {
            ServiceCategory c = seedCategory("Electrical", 1, true);

            service.deactivateCategory(c.getId());

            assertThat(categoryRepository.findById(c.getId()).orElseThrow().isActive()).isFalse();
            assertThat(service.getActiveCatalog()).isEmpty();
        }

        @Test
        void reactivatedCategoryReappearsInCustomerListing() {
            ServiceCategory c = seedCategory("Cleaning", 1, false);

            service.activateCategory(c.getId());

            assertThat(categoryRepository.findById(c.getId()).orElseThrow().isActive()).isTrue();
            assertThat(service.getActiveCatalog()).extracting(CategoryView::name).containsExactly("Cleaning");
        }

        @Test
        void deactivationExcludesSubcategoriesFromCustomerListing() {
            ServiceCategory c = seedCategory("Painting", 1, true);
            service.createSubcategory(c.getId(), validSub("Wall painting"));
            // Active before deactivation.
            assertThat(service.getActiveCatalog()).hasSize(1);
            assertThat(service.getActiveCatalog().get(0).subcategories()).hasSize(1);

            service.deactivateCategory(c.getId());

            // Category (and therefore its subcategories) absent from the customer API.
            assertThat(service.getActiveCatalog()).isEmpty();
        }
    }

    // ===================== Subcategory under deactivated parent =====================

    @Nested
    class SubcategoryParentValidation {

        @Test
        void subcategoryUnderActiveParent_isAccepted() {
            ServiceCategory c = seedCategory("HVAC", 1, true);

            ServiceSubcategory sub = service.createSubcategory(c.getId(), validSub("AC repair"));

            assertThat(sub.getCategoryId()).isEqualTo(c.getId());
            assertThat(subcategoryRepository.findByCategoryId(c.getId())).hasSize(1);
        }

        @Test
        void subcategoryUnderDeactivatedParent_isRejected() {
            ServiceCategory c = seedCategory("Locksmith", 1, false);

            assertThatThrownBy(() -> service.createSubcategory(c.getId(), validSub("Lock change")))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> {
                        CatalogException ce = (CatalogException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo("PARENT_CATEGORY_DEACTIVATED");
                        assertThat(ce.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ce.getMessage()).contains(c.getId().toString());
                    });
        }

        @Test
        void subcategoryUnderNonExistentParent_isRejected() {
            UUID missing = UUID.randomUUID();

            assertThatThrownBy(() -> service.createSubcategory(missing, validSub("Anything")))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> {
                        CatalogException ce = (CatalogException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo("PARENT_CATEGORY_NOT_FOUND");
                        assertThat(ce.getMessage()).contains(missing.toString());
                    });
        }
    }

    // ===================== Subcategory attribute validation (Req 3.7) =====================

    @Nested
    class SubcategoryAttributeValidation {

        @Test
        void basePriceBelowMinimum_isRejected() {
            ServiceCategory c = seedCategory("Cat", 1, true);
            SubcategoryRequest req = new SubcategoryRequest("s", new BigDecimal("0.00"), 60, List.of(), false);
            assertThatThrownBy(() -> service.createSubcategory(c.getId(), req))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> assertThat(((CatalogException) ex).getMessage()).contains("0.01"));
        }

        @Test
        void basePriceAboveMaximum_isRejected() {
            ServiceCategory c = seedCategory("Cat", 1, true);
            SubcategoryRequest req = new SubcategoryRequest("s", new BigDecimal("1000000.00"), 60, List.of(), false);
            assertThatThrownBy(() -> service.createSubcategory(c.getId(), req))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> assertThat(((CatalogException) ex).getMessage()).contains("999999.99"));
        }

        @Test
        void durationOutOfRange_isRejected() {
            ServiceCategory c = seedCategory("Cat", 1, true);
            SubcategoryRequest tooLong = new SubcategoryRequest("s", new BigDecimal("10.00"), 481, List.of(), false);
            assertThatThrownBy(() -> service.createSubcategory(c.getId(), tooLong))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> assertThat(((CatalogException) ex).getMessage()).contains("480"));
        }

        @Test
        void tooManySkillTags_isRejected() {
            ServiceCategory c = seedCategory("Cat", 1, true);
            List<String> tags = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) tags.add("t" + i);
            SubcategoryRequest req = new SubcategoryRequest("s", new BigDecimal("10.00"), 60, tags, false);
            assertThatThrownBy(() -> service.createSubcategory(c.getId(), req))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> assertThat(((CatalogException) ex).getMessage()).contains("20"));
        }

        @Test
        void boundaryValues_areAccepted() {
            ServiceCategory c = seedCategory("Cat", 1, true);
            List<String> tags = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) tags.add("t" + i);
            SubcategoryRequest min = new SubcategoryRequest("min", new BigDecimal("0.01"), 1, tags, true);
            SubcategoryRequest max = new SubcategoryRequest("max", new BigDecimal("999999.99"), 480, List.of(), false);
            assertThat(service.createSubcategory(c.getId(), min).getBasePrice()).isEqualByComparingTo("0.01");
            assertThat(service.createSubcategory(c.getId(), max).getEstimatedDurationMin()).isEqualTo(480);
        }
    }

    // ===================== Category deletion blockers (Req 3.6) =====================

    @Nested
    class CategoryDeletion {

        @Test
        void deletionWithoutBlockers_succeeds() {
            ServiceCategory c = seedCategory("Removable", 1, true);
            service.createSubcategory(c.getId(), validSub("sub"));
            when(dependencyPort.findBlockers(c.getId())).thenReturn(CategoryBlockers.none());

            service.deleteCategory(c.getId());

            assertThat(categoryRepository.findById(c.getId())).isEmpty();
            assertThat(subcategoryRepository.findByCategoryId(c.getId())).isEmpty();
        }

        @Test
        void deletionWithActiveProvidersOrBookings_isRejectedWithCountsAndIds() {
            ServiceCategory c = seedCategory("Busy", 1, true);
            CategoryBlockers blockers = new CategoryBlockers(
                    List.of("prov-1", "prov-2"), List.of("BK-100"));
            when(dependencyPort.findBlockers(c.getId())).thenReturn(blockers);

            assertThatThrownBy(() -> service.deleteCategory(c.getId()))
                    .isInstanceOf(CatalogException.class)
                    .satisfies(ex -> {
                        CatalogException ce = (CatalogException) ex;
                        assertThat(ce.getErrorCode()).isEqualTo("CATEGORY_HAS_ACTIVE_DEPENDENCIES");
                        assertThat(ce.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ce.getMessage()).contains("2 active provider").contains("1 active booking");
                        assertThat(ce.getDetails())
                                .contains("activeProviderCount=2")
                                .contains("provider=prov-1")
                                .contains("provider=prov-2")
                                .contains("activeBookingCount=1")
                                .contains("booking=BK-100");
                    });
            // Category still present — deletion was rejected.
            assertThat(categoryRepository.findById(c.getId())).isPresent();
        }
    }

    // ===================== Cache invalidation on Admin writes (Req 3.8) =====================

    @Nested
    class CacheInvalidation {

        @Test
        void everyAdminWriteInvalidatesTheCache() {
            lenient().when(dependencyPort.findBlockers(any())).thenReturn(CategoryBlockers.none());

            cache.resetCounters();
            ServiceCategory c = service.createCategory(new CategoryRequest("A", "d", "i", 1));
            service.updateCategory(c.getId(), new CategoryRequest("A2", "d", "i", 1));
            ServiceSubcategory sub = service.createSubcategory(c.getId(), validSub("s"));
            service.updateSubcategory(sub.getId(), validSub("s2"));
            service.deactivateSubcategory(sub.getId());
            service.activateSubcategory(sub.getId());
            service.deactivateCategory(c.getId());
            service.activateCategory(c.getId());
            service.deleteSubcategory(sub.getId());
            service.deleteCategory(c.getId());

            // 10 mutating operations => 10 invalidations.
            assertThat(cache.invalidations()).isEqualTo(10);
        }

        @Test
        void customerListingIsServedReadThrough_missThenHit() {
            seedCategory("Cached", 1, true);
            cache.resetCounters();

            // First read: cache miss populates the cache.
            List<CategoryView> first = service.getActiveCatalog();
            assertThat(first).hasSize(1);
            assertThat(cache.puts()).isEqualTo(1);

            // Second read: served from cache, no repopulation.
            List<CategoryView> second = service.getActiveCatalog();
            assertThat(second).hasSize(1);
            assertThat(cache.puts()).isEqualTo(1);
            assertThat(cache.hits()).isEqualTo(1);
        }
    }
}
