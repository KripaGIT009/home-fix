package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * Unit tests for {@link BookingQueryService} (Requirement 28.7) over an in-memory repository:
 * history is the caller's own, newest first and 1-based; detail is visible to the customer, the
 * assigned provider and staff, and a 404 — indistinguishable from "no such booking" — to anyone
 * else; and an unresolvable service name degrades to a label instead of failing the read.
 */
class BookingQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PLUMBING = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private InMemoryBookingRepository repository;
    private FakeCatalog catalog;
    private BookingQueryService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        catalog = new FakeCatalog(Map.of(PLUMBING, "Tap repair"));
        service = new BookingQueryService(repository, catalog, mock(JobMediaRepository.class),
                mock(PartsLineItemRepository.class), id -> Optional.empty());
    }

    /** Saves a booking for {@code customer} created {@code minutes} after {@link #T0}. */
    private Booking place(UUID customer, String reference, long minutes) {
        Instant createdAt = T0.plus(Duration.ofMinutes(minutes));
        return repository.save(Bookings.placed(customer, PLUMBING, reference, createdAt,
                createdAt.plus(Duration.ofDays(2))));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BookingException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_FOUND");
                });
    }

    private static void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BookingException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                });
    }

    @Nested
    class History {

        @Test
        void pagesTheCallersBookingsNewestFirstWithOneBasedPages() {
            for (int i = 1; i <= 5; i++) {
                place(CUSTOMER, "HFX-" + i, i);
            }
            place(OTHER_CUSTOMER, "HFX-OTHER", 99);

            BookingQueryService.HistoryPage first = service.history(CUSTOMER, 1, 2);
            BookingQueryService.HistoryPage second = service.history(CUSTOMER, 2, 2);
            BookingQueryService.HistoryPage last = service.history(CUSTOMER, 3, 2);

            assertThat(first.items()).extracting(v -> v.booking().getReference())
                    .containsExactly("HFX-5", "HFX-4");
            assertThat(second.items()).extracting(v -> v.booking().getReference())
                    .containsExactly("HFX-3", "HFX-2");
            assertThat(last.items()).extracting(v -> v.booking().getReference())
                    .containsExactly("HFX-1");
            assertThat(first.page()).isEqualTo(1);
            assertThat(second.page()).isEqualTo(2);
            assertThat(first.pageSize()).isEqualTo(2);
            assertThat(first.totalItems()).isEqualTo(5);
            assertThat(first.totalPages()).isEqualTo(3);
        }

        @Test
        void pagePastTheEndIsEmptyWithRealTotalsAndSkipsTheCatalog() {
            place(CUSTOMER, "HFX-1", 1);

            BookingQueryService.HistoryPage page = service.history(CUSTOMER, 4, 10);

            assertThat(page.items()).isEmpty();
            assertThat(page.page()).isEqualTo(4);
            assertThat(page.totalItems()).isEqualTo(1);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(catalog.calls).isZero();
        }

        @Test
        void callerWithNoBookingsGetsAnEmptyHistoryNotSomebodyElses() {
            // Staff reach history too; it is keyed by the caller, so they see only their own.
            place(CUSTOMER, "HFX-1", 1);

            BookingQueryService.HistoryPage page = service.history(STRANGER, 1, 10);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalItems()).isZero();
            assertThat(page.totalPages()).isZero();
        }

        @Test
        void resolvesServiceNamesWithOneCatalogCallPerPage() {
            place(CUSTOMER, "HFX-1", 1);
            place(CUSTOMER, "HFX-2", 2);

            BookingQueryService.HistoryPage page = service.history(CUSTOMER, 1, 10);

            assertThat(page.items()).extracting(BookingQueryService.BookingView::serviceName)
                    .containsOnly("Tap repair");
            assertThat(catalog.calls).isEqualTo(1);
        }

        @Test
        void rejectsPageBelowOne() {
            assertValidationError(() -> service.history(CUSTOMER, 0, 10));
            assertValidationError(() -> service.history(CUSTOMER, -1, 10));
        }

        @Test
        void rejectsPageSizeOutsideOneToFifty() {
            assertValidationError(() -> service.history(CUSTOMER, 1, 0));
            assertValidationError(() -> service.history(CUSTOMER, 1, 51));
            assertThat(service.history(CUSTOMER, 1, 1).pageSize()).isEqualTo(1);
            assertThat(service.history(CUSTOMER, 1, 50).pageSize()).isEqualTo(50);
        }

        @Test
        void rejectsAPageWhoseRowOffsetWouldOverflow() {
            assertValidationError(() -> service.history(CUSTOMER, Integer.MAX_VALUE, 50));
        }
    }

    @Nested
    class Detail {

        private Booking booking;

        @BeforeEach
        void placeBooking() {
            booking = place(CUSTOMER, "HFX-20261002-ABC123", 0);
        }

        @Test
        void customerSeesTheirOwnBooking() {
            assertThat(service.detail(booking.getId().toString(), CUSTOMER, false).booking())
                    .isSameAs(booking);
        }

        @Test
        void assignedProviderSeesTheBooking() {
            booking.setProviderId(PROVIDER);

            assertThat(service.detail(booking.getId().toString(), PROVIDER, false).booking())
                    .isSameAs(booking);
        }

        @Test
        void staffSeeAnyBooking() {
            assertThat(service.detail(booking.getId().toString(), STRANGER, true).booking())
                    .isSameAs(booking);
        }

        @Test
        void strangerGetsTheSame404AsForAMissingBooking() {
            assertNotFound(() -> service.detail(booking.getId().toString(), STRANGER, false));
            assertNotFound(() -> service.detail(UUID.randomUUID().toString(), CUSTOMER, false));
        }

        @Test
        void unassignedBookingIsNotVisibleToAProvider() {
            assertNotFound(() -> service.detail(booking.getId().toString(), PROVIDER, false));
        }

        @Test
        void providerLosesSightOfABookingReassignedAway() {
            booking.setProviderId(UUID.randomUUID());

            assertNotFound(() -> service.detail(booking.getId().toString(), PROVIDER, false));
        }

        @Test
        void acceptsTheReferenceUnderTheSameOwnershipRules() {
            assertThat(service.detail("HFX-20261002-ABC123", CUSTOMER, false).booking())
                    .isSameAs(booking);
            assertNotFound(() -> service.detail("HFX-20261002-ABC123", STRANGER, false));
            assertNotFound(() -> service.detail("HFX-20261002-NOPE00", CUSTOMER, false));
        }

        @Test
        void aNonCanonicalUuidIsTreatedAsAReferenceNotAnId() {
            // UUID.fromString("1-2-3-4-5") parses; it must not be mistaken for a booking id.
            assertNotFound(() -> service.detail("1-2-3-4-5", CUSTOMER, false));
            assertNotFound(() -> service.detail(" ", CUSTOMER, false));
        }

        @Test
        void unknownSubcategoryIsLabelledWithTheFallback() {
            Booking unlisted = repository.save(Bookings.placed(CUSTOMER, UUID.randomUUID(),
                    "HFX-UNLISTED", T0, null));

            assertThat(service.detail(unlisted.getId().toString(), CUSTOMER, false).serviceName())
                    .isEqualTo(BookingQueryService.FALLBACK_SERVICE_NAME);
        }

        @Test
        void catalogFailureDegradesTheLabelInsteadOfFailingTheRead() {
            catalog.failure = new IllegalStateException("catalog down");

            BookingQueryService.BookingView view = service.detail(booking.getId().toString(), CUSTOMER, false);

            assertThat(view.booking()).isSameAs(booking);
            assertThat(view.serviceName()).isEqualTo("Service");
        }
    }

    @Nested
    class AdminSearch {

        private static List<String> references(List<BookingQueryService.BookingView> views) {
            return views.stream().map(v -> v.booking().getReference()).toList();
        }

        @Test
        void listsEveryCustomersBookingsNewestFirst() {
            place(CUSTOMER, "HFX-20261001-AAA111", 1);
            place(OTHER_CUSTOMER, "HFX-20261001-BBB222", 3);
            place(CUSTOMER, "HFX-20261001-CCC333", 2);

            assertThat(references(service.adminSearch(null, null)))
                    .containsExactly("HFX-20261001-BBB222", "HFX-20261001-CCC333", "HFX-20261001-AAA111");
        }

        @Test
        void searchIsACaseInsensitiveSubstringOfTheReference() {
            place(CUSTOMER, "HFX-20261001-ABC123", 1);
            place(OTHER_CUSTOMER, "HFX-20261001-XYZ789", 2);

            assertThat(references(service.adminSearch("  abc1 ", null))).containsExactly("HFX-20261001-ABC123");
            assertThat(references(service.adminSearch("hfx-2026", null))).hasSize(2);
            assertThat(service.adminSearch("NOPE", null)).isEmpty();
        }

        @Test
        void aBookingUuidFindsThatBooking() {
            Booking target = place(CUSTOMER, "HFX-20261001-ABC123", 1);
            place(OTHER_CUSTOMER, "HFX-20261001-XYZ789", 2);

            assertThat(references(service.adminSearch(target.getId().toString().toUpperCase(), null)))
                    .containsExactly("HFX-20261001-ABC123");
            assertThat(service.adminSearch(UUID.randomUUID().toString(), null)).isEmpty();
        }

        @Test
        void statusFiltersTheResult() {
            Booking cancelled = place(CUSTOMER, "HFX-20261001-ABC123", 1);
            cancelled.applyStatus(BookingStatus.CANCELLED);
            place(CUSTOMER, "HFX-20261001-XYZ789", 2);

            assertThat(references(service.adminSearch(null, BookingStatus.CANCELLED)))
                    .containsExactly("HFX-20261001-ABC123");
            assertThat(service.adminSearch(cancelled.getId().toString(), BookingStatus.CREATED)).isEmpty();
        }

        @Test
        void isCappedAtTheAdminListLimit() {
            for (int i = 0; i < BookingQueryService.ADMIN_LIST_LIMIT + 5; i++) {
                place(CUSTOMER, "HFX-20261001-N" + i, i);
            }

            List<BookingQueryService.BookingView> views = service.adminSearch("", null);

            assertThat(views).hasSize(BookingQueryService.ADMIN_LIST_LIMIT);
            // The newest are kept, not the oldest.
            assertThat(views.get(0).booking().getReference())
                    .isEqualTo("HFX-20261001-N" + (BookingQueryService.ADMIN_LIST_LIMIT + 4));
        }

        @Test
        void labelsTheWholeListWithOneCatalogCallAndNoneWhenEmpty() {
            place(CUSTOMER, "HFX-20261001-AAA111", 1);
            place(CUSTOMER, "HFX-20261001-BBB222", 2);

            List<BookingQueryService.BookingView> views = service.adminSearch(null, null);

            assertThat(views).extracting(BookingQueryService.BookingView::serviceName)
                    .containsOnly("Tap repair");
            assertThat(catalog.calls).isEqualTo(1);

            service.adminSearch("NOPE", null);
            assertThat(catalog.calls).isEqualTo(1);
        }

        @Test
        void catalogFailureDegradesTheLabels() {
            place(CUSTOMER, "HFX-20261001-AAA111", 1);
            catalog.failure = new IllegalStateException("catalog down");

            assertThat(service.adminSearch(null, null))
                    .extracting(BookingQueryService.BookingView::serviceName)
                    .containsExactly(BookingQueryService.FALLBACK_SERVICE_NAME);
        }
    }

    @Nested
    class DisplayValues {

        @Test
        void dateIsTheScheduledTimeElseTheCreationTime() {
            Instant scheduled = T0.plus(Duration.ofDays(3));
            Booking scheduledBooking = Bookings.placed(CUSTOMER, PLUMBING, "HFX-S", T0, scheduled);
            Booking unscheduled = Bookings.placed(CUSTOMER, PLUMBING, "HFX-U", T0, null);

            assertThat(new BookingQueryService.BookingView(scheduledBooking, "x").date()).isEqualTo(scheduled);
            assertThat(new BookingQueryService.BookingView(unscheduled, "x").date()).isEqualTo(T0);
        }

        @Test
        void amountIsTheFinalTotalElseTheEstimate() {
            Booking b = Bookings.placed(CUSTOMER, PLUMBING, "HFX-A", T0, null);
            BookingQueryService.BookingView view = new BookingQueryService.BookingView(b, "x");

            assertThat(view.amount()).isEqualByComparingTo("100.00");
            b.setFinalTotal(new BigDecimal("180.50"));
            assertThat(view.amount()).isEqualByComparingTo("180.50");
        }
    }

    /** Catalog double that counts name lookups and can be made to fail. */
    private static final class FakeCatalog implements CatalogClientPort {

        private final Map<UUID, String> names;
        private int calls;
        private RuntimeException failure;

        FakeCatalog(Map<UUID, String> names) {
            this.names = names;
        }

        @Override
        public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
            return true;
        }

        @Override
        public Map<UUID, String> subcategoryNames() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return names;
        }
    }
}
