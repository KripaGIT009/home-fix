package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.booking.api.dto.BookingDetailResponse;
import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.FakeTenantDirectory;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * The booking detail's {@code tenantName} (Requirement MT-6.4, MT-13.1): present while a
 * Tenant-assigned booking waits for the Provider's confirmation, absent otherwise, and never a
 * reason for the read to fail.
 */
class BookingQueryTenantNameTest {

    private InMemoryBookingRepository repository;
    private FakeTenantDirectory directory;
    private BookingQueryService service;
    private UUID tenantId;
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        directory = new FakeTenantDirectory();
        tenantId = directory.addTenant("Ara Home Services", "ACTIVE").tenantId();
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }

            @Override
            public Map<UUID, String> subcategoryNames() {
                return Map.of();
            }
        };
        service = new BookingQueryService(repository, catalog, mock(JobMediaRepository.class),
                mock(PartsLineItemRepository.class), id -> Optional.empty(), directory);
    }

    private Booking booking(BookingStatus status, UUID tenant) {
        Booking booking = Bookings.inState(status);
        booking.setProviderId(providerId);
        booking.setTenantId(tenant);
        return repository.save(booking);
    }

    private String tenantNameSeenBy(Booking booking, UUID caller) {
        return BookingDetailResponse.of(service.detail(booking.getId().toString(), caller, false)).tenantName();
    }

    @Test
    void anAssignedBookingNamesTheAssigningTenantToProviderAndCustomer() {
        Booking booking = booking(BookingStatus.PROVIDER_ASSIGNED, tenantId);

        assertThat(tenantNameSeenBy(booking, providerId)).isEqualTo("Ara Home Services");
        assertThat(tenantNameSeenBy(booking, booking.getCustomerId())).isEqualTo("Ara Home Services");
    }

    @Test
    void onceAcceptedTheNameIsNoLongerLookedUp() {
        Booking booking = booking(BookingStatus.PROVIDER_ACCEPTED, tenantId);
        directory.down = true; // would fail if it were asked

        assertThat(tenantNameSeenBy(booking, providerId)).isNull();
    }

    @Test
    void aBookingWithoutATenantHasNoName() {
        assertThat(tenantNameSeenBy(booking(BookingStatus.PROVIDER_ASSIGNED, null), providerId)).isNull();
    }

    @Test
    void anUnreachableProviderServiceLeavesTheNameOut() {
        Booking booking = booking(BookingStatus.PROVIDER_ASSIGNED, tenantId);
        directory.down = true;

        assertThat(tenantNameSeenBy(booking, providerId)).isNull();
        assertThat(service.detail(booking.getId().toString(), providerId, false).booking().getStatus())
                .isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
    }
}
