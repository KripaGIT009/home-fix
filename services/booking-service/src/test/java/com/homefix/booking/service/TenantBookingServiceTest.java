package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.BookingTenantCandidate;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.event.ProviderAssignedEvent;
import com.homefix.booking.service.TenantBookingService.TenantBookingView;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.FakeTenantDirectory;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.booking.support.InMemoryBookingTenantCandidateRepository;
import com.homefix.booking.support.MutableClock;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Rules of the Tenant Portal's booking side (Requirements MT-5, MT-8.3, MT-10): queue and list
 * scoping per Tenant (Property MT5), who may assign what (Property MT4), the errors of each refusal,
 * and the {@code ProviderAssigned} event naming the Tenant. The race between two real transactions
 * (Property MT3) is {@code TenantAssignmentConcurrencyTest}'s; here the transaction is a
 * pass-through over in-memory repositories.
 */
class TenantBookingServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");
    private static final UUID SUBCATEGORY = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private InMemoryBookingRepository repository;
    private InMemoryBookingTenantCandidateRepository candidates;
    private FakeTenantDirectory directory;
    private OutboxEventPublisher outbox;
    private MutableClock clock;
    private TenantBookingService service;

    private TenantSummary ara;
    private TenantSummary bhojpur;
    private final UUID araAdmin = UUID.randomUUID();
    private final UUID bhojpurAdmin = UUID.randomUUID();
    private final UUID araProvider = UUID.randomUUID();
    private final UUID bhojpurProvider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        candidates = new InMemoryBookingTenantCandidateRepository();
        repository = new InMemoryBookingRepository(candidates);
        directory = new FakeTenantDirectory();
        outbox = mock(OutboxEventPublisher.class);
        clock = new MutableClock(T0);
        BookingAuditRepository audits = mock(BookingAuditRepository.class);
        when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BookingTransitionService transitions = new BookingTransitionService(new BookingStateMachine(),
                audits, new BookingLifecycleEventPublisher(outbox, clock), clock);
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }

            @Override
            public Map<UUID, String> subcategoryNames() {
                return Map.of(SUBCATEGORY, "Tap repair");
            }
        };
        BookingQueryService queries = new BookingQueryService(repository, catalog,
                mock(JobMediaRepository.class), mock(PartsLineItemRepository.class), id -> Optional.empty());
        service = new TenantBookingService(repository, candidates,
                new CallerTenantResolver(directory, clock), directory, transitions, queries,
                id -> Optional.of(new ServiceAddress("12 Station Rd, Ara", 25.556, 84.663)),
                TransactionOperations.withoutTransaction());

        ara = directory.addTenant("Ara Home Services", "ACTIVE");
        bhojpur = directory.addTenant("Bhojpur Fixers", "ACTIVE");
        directory.adminToTenant.put(araAdmin, ara.tenantId());
        directory.adminToTenant.put(bhojpurAdmin, bhojpur.tenantId());
        directory.addMember(ara.tenantId(), araProvider, true);
        directory.addMember(bhojpur.tenantId(), bhojpurProvider, true);
    }

    /** A booking that fell back to the given Tenants {@code minutes} after T0. */
    private Booking queued(String reference, long minutes, UUID... tenantIds) {
        Booking booking = Bookings.placed(UUID.randomUUID(), SUBCATEGORY, reference, T0, T0.plus(Duration.ofDays(1)));
        booking.applyStatus(BookingStatus.AWAITING_ASSIGNMENT);
        booking.setQueuedForAssignmentAt(T0.plus(Duration.ofMinutes(minutes)));
        repository.save(booking);
        for (UUID tenantId : tenantIds) {
            candidates.save(new BookingTenantCandidate(booking.getId(), tenantId));
        }
        return booking;
    }

    private static HttpStatus statusOf(Throwable e) {
        return ((BookingException) e).getStatus();
    }

    private static String codeOf(Throwable e) {
        return ((BookingException) e).getErrorCode();
    }

    // ----- queue (Requirement MT-5.1, Property MT5) ----------------------------

    @Test
    void eachTenantSeesOnlyItsOwnQueueOldestFirst() {
        Booking shared = queued("HFX-SHARED", 5, ara.tenantId(), bhojpur.tenantId());
        Booking araOnly = queued("HFX-ARA", 1, ara.tenantId());
        Booking bhojpurOnly = queued("HFX-BHOJPUR", 2, bhojpur.tenantId());

        assertThat(service.queue(araAdmin)).extracting(v -> v.view().booking().getReference())
                .containsExactly("HFX-ARA", "HFX-SHARED");
        assertThat(service.queue(bhojpurAdmin)).extracting(v -> v.view().booking().getReference())
                .containsExactly("HFX-BHOJPUR", "HFX-SHARED");
        TenantBookingView row = service.queue(araAdmin).get(0);
        assertThat(row.view().serviceName()).isEqualTo("Tap repair");
        assertThat(row.address().label()).isEqualTo("12 Station Rd, Ara");
        assertThat(shared.getId()).isNotEqualTo(araOnly.getId()).isNotEqualTo(bhojpurOnly.getId());
    }

    @Test
    void aDeclinedBookingReturnsToItsTenantsQueueOnly() {
        Booking declined = queued("HFX-DECLINED", 1, ara.tenantId(), bhojpur.tenantId());
        declined.setTenantId(ara.tenantId()); // assigned by Ara, then declined by Ara's provider

        assertThat(service.queue(araAdmin)).extracting(v -> v.view().booking().getId())
                .containsExactly(declined.getId());
        assertThat(service.queue(bhojpurAdmin)).isEmpty();
    }

    @Test
    void tenantBookingsAreTheTenantsOwnNewestFirstAndFilterableByStatus() {
        Booking older = Bookings.placed(UUID.randomUUID(), SUBCATEGORY, "HFX-OLD", T0, null);
        older.setTenantId(ara.tenantId());
        older.applyStatus(BookingStatus.PROVIDER_ACCEPTED);
        Booking newer = Bookings.placed(UUID.randomUUID(), SUBCATEGORY, "HFX-NEW", T0.plusSeconds(60), null);
        newer.setTenantId(ara.tenantId());
        newer.applyStatus(BookingStatus.JOB_STARTED);
        Booking theirs = Bookings.placed(UUID.randomUUID(), SUBCATEGORY, "HFX-THEIRS", T0, null);
        theirs.setTenantId(bhojpur.tenantId());
        repository.save(older);
        repository.save(newer);
        repository.save(theirs);

        assertThat(service.bookings(araAdmin, null)).extracting(v -> v.view().booking().getReference())
                .containsExactly("HFX-NEW", "HFX-OLD");
        assertThat(service.bookings(araAdmin, BookingStatus.PROVIDER_ACCEPTED))
                .extracting(v -> v.view().booking().getReference()).containsExactly("HFX-OLD");
    }

    // ----- caller's Tenant (Requirement MT-10.1, MT-1.4) -----------------------

    @Test
    void aCallerWhoAdministersNoTenantGets404() {
        assertThatThrownBy(() -> service.queue(UUID.randomUUID()))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TENANT_NOT_FOUND"));
    }

    @Test
    void aSuspendedTenantsAdminIsRefusedWith403() {
        directory.tenants.put(ara.tenantId(), new TenantSummary(ara.tenantId(), ara.name(), "SUSPENDED"));

        assertThatThrownBy(() -> service.queue(araAdmin))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TENANT_SUSPENDED"));
    }

    @Test
    void aProviderServiceOutageIs503() {
        directory.down = true;

        assertThatThrownBy(() -> service.queue(araAdmin))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void theCallersTenantIsCachedForAMinute() {
        service.queue(araAdmin);
        service.queue(araAdmin);
        assertThat(directory.byAdminCalls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        service.queue(araAdmin);
        assertThat(directory.byAdminCalls.get()).isEqualTo(2);
    }

    // ----- assignment (Requirement MT-5.2 to MT-5.6) ---------------------------

    @Test
    void assigningRecordsTenantAndProviderAndPublishesProviderAssignedNamingTheTenant() {
        Booking booking = queued("HFX-ASSIGN", 1, ara.tenantId(), bhojpur.tenantId());

        TenantBookingView result = service.assign(araAdmin, booking.getReference(), araProvider);

        Booking assigned = result.view().booking();
        assertThat(assigned.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        assertThat(assigned.getTenantId()).isEqualTo(ara.tenantId());
        assertThat(assigned.getProviderId()).isEqualTo(araProvider);
        assertThat(assigned.getQueuedForAssignmentAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(ProviderAssignedEvent.AGGREGATE_TYPE), eq(booking.getId()),
                eq(ProviderAssignedEvent.EVENT_TYPE), payload.capture());
        ProviderAssignedEvent event = (ProviderAssignedEvent) payload.getValue();
        assertThat(event.providerId()).isEqualTo(araProvider);
        assertThat(event.customerId()).isEqualTo(booking.getCustomerId());
        assertThat(event.tenantId()).isEqualTo(ara.tenantId());
        assertThat(event.tenantName()).isEqualTo("Ara Home Services");
    }

    @Test
    void anotherTenantsProviderIsNotAssignable() {
        Booking booking = queued("HFX-X", 1, ara.tenantId(), bhojpur.tenantId());

        assertThatThrownBy(() -> service.assign(araAdmin, booking.getReference(), bhojpurProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PROVIDER_NOT_ASSIGNABLE"))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(booking.getProviderId()).isNull();
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void anUnapprovedMemberIsNotAssignable() {
        UUID pending = UUID.randomUUID();
        directory.addMember(ara.tenantId(), pending, false);
        Booking booking = queued("HFX-P", 1, ara.tenantId());

        assertThatThrownBy(() -> service.assign(araAdmin, booking.getReference(), pending))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("PROVIDER_NOT_ASSIGNABLE"));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
    }

    @Test
    void aBookingOutsideTheCallersQueueIs404LikeAMissingOne() {
        Booking theirs = queued("HFX-THEIRS", 1, bhojpur.tenantId());

        assertThatThrownBy(() -> service.assign(araAdmin, theirs.getId().toString(), araProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING_NOT_FOUND"));
        assertThatThrownBy(() -> service.assign(araAdmin, "HFX-NOPE", araProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING_NOT_FOUND"));
        assertThat(theirs.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
    }

    @Test
    void aBookingAnotherCandidateAlreadyAssignedIs409() {
        Booking booking = queued("HFX-WON", 1, ara.tenantId(), bhojpur.tenantId());
        service.assign(bhojpurAdmin, booking.getReference(), bhojpurProvider);

        assertThatThrownBy(() -> service.assign(araAdmin, booking.getReference(), araProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING_NOT_ASSIGNABLE"));
        assertThat(booking.getTenantId()).isEqualTo(bhojpur.tenantId());
        assertThat(booking.getProviderId()).isEqualTo(bhojpurProvider);
    }

    @Test
    void aDeclinedBookingCanBeReassignedOnlyByItsTenant() {
        Booking booking = queued("HFX-BACK", 1, ara.tenantId(), bhojpur.tenantId());
        booking.setTenantId(ara.tenantId());

        assertThatThrownBy(() -> service.assign(bhojpurAdmin, booking.getReference(), bhojpurProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING_NOT_ASSIGNABLE"));
        assertThat(service.assign(araAdmin, booking.getReference(), araProvider).view().booking().getStatus())
                .isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
    }

    @Test
    void aTimedOutOrCancelledBookingIs409() {
        Booking booking = queued("HFX-LATE", 1, ara.tenantId());
        booking.applyStatus(BookingStatus.SEARCHING_FAILED);

        assertThatThrownBy(() -> service.assign(araAdmin, booking.getReference(), araProvider))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING_NOT_ASSIGNABLE"));
    }

    @Test
    void anUnreachableProviderServiceRefusesTheAssignmentWith503() {
        Booking booking = queued("HFX-DOWN", 1, ara.tenantId());
        service.queue(araAdmin); // the caller's Tenant is cached; membership is not
        directory.down = true;

        assertThatThrownBy(() -> service.assign(araAdmin, booking.getReference(), araProvider))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
    }
}
