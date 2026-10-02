package com.homefix.booking.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.booking.address.CustomerAddressPort;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobMedia;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItem;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.service.BookingQueryService;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * Web-layer tests for {@link BookingQueryController} (Requirement 28.7): the JSON contract the
 * customer app's history and tracking screens read, the ownership rules (customer, assigned
 * provider and staff see a booking; anyone else gets the same 404 as for a missing one), history
 * paging, and validation through the shared error envelope.
 *
 * <p>Runs a standalone MockMvc over the real {@link BookingQueryService} and an in-memory
 * repository, with the caller placed in the {@code SecurityContextHolder} the way the shared JWT
 * filter does. The JSON converter is configured as Spring Boot configures it (ISO-8601 dates),
 * which the standalone setup otherwise would not do.
 */
class BookingQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PLUMBING = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private InMemoryBookingRepository repository;
    private JobMediaRepository media;
    private PartsLineItemRepository parts;
    private final Map<UUID, ServiceAddress> savedAddresses = new HashMap<>();
    private final CustomerAddressPort addresses = id -> Optional.ofNullable(savedAddresses.get(id));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        media = mock(JobMediaRepository.class);
        parts = mock(PartsLineItemRepository.class);
        CatalogClientPort catalog = new CatalogClientPort() {
            @Override
            public boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId) {
                return true;
            }

            @Override
            public Map<UUID, String> subcategoryNames() {
                return Map.of(PLUMBING, "Tap repair");
            }
        };
        BookingQueryController controller = new BookingQueryController(
                new BookingQueryService(repository, catalog, media, parts, addresses), new CallerIdentity());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(UUID subject, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                subject.toString(), null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    private Booking place(String reference, long minutes, Instant scheduledAt) {
        return repository.save(Bookings.placed(CUSTOMER, PLUMBING, reference,
                T0.plus(Duration.ofMinutes(minutes)), scheduledAt));
    }

    private ResultActions getDetail(Object key) throws Exception {
        return mvc.perform(get("/bookings/{bookingKey}", key.toString()));
    }

    // ----- history ---------------------------------------------------------

    @Test
    void historyReturnsTheAppsPageShapeWithDefaults() throws Exception {
        Instant scheduled = T0.plus(Duration.ofDays(2));
        Booking booking = place("HFX-20260901-AAA111", 0, scheduled);
        booking.applyStatus(BookingStatus.SEARCHING_PROVIDER);
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(get("/bookings/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].bookingId").value(booking.getId().toString()))
                .andExpect(jsonPath("$.items[0].referenceNumber").value("HFX-20260901-AAA111"))
                .andExpect(jsonPath("$.items[0].status").value("SEARCHING_PROVIDER"))
                .andExpect(jsonPath("$.items[0].serviceName").value("Tap repair"))
                .andExpect(jsonPath("$.items[0].date").value("2026-09-03T08:00:00Z"))
                .andExpect(jsonPath("$.items[0].amount").value(100.00))
                .andExpect(jsonPath("$.items[0].currency").value("INR"));
    }

    @Test
    void historyPagesNewestFirstWithOneBasedPageNumbers() throws Exception {
        for (int i = 1; i <= 3; i++) {
            place("HFX-" + i, i, null);
        }
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(get("/bookings/history").param("page", "1").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].referenceNumber").value("HFX-3"))
                .andExpect(jsonPath("$.items[1].referenceNumber").value("HFX-2"))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/bookings/history").param("page", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].referenceNumber").value("HFX-1"))
                // No scheduled time: the row is dated by when it was placed.
                .andExpect(jsonPath("$.items[0].date").value("2026-09-01T08:01:00Z"));
    }

    @Test
    void staffHistoryIsTheirOwnNotEveryones() throws Exception {
        place("HFX-1", 1, null);
        authenticateAs(STRANGER, "ADMIN");

        mvc.perform(get("/bookings/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void historyRejectsOutOfRangePagingWith400Envelope() throws Exception {
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(get("/bookings/history").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mvc.perform(get("/bookings/history").param("pageSize", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mvc.perform(get("/bookings/history").param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void historyRejectsNonNumericPagingWith400Envelope() throws Exception {
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(get("/bookings/history").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("page: must be int"));
    }

    @Test
    void nonUuidSubjectIsRejectedAs401() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "not-a-uuid", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        mvc.perform(get("/bookings/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRINCIPAL"));
    }

    // ----- detail ----------------------------------------------------------

    @Test
    void customerSeesTheirBookingWithTheTrackingFields() throws Exception {
        Booking booking = place("HFX-20260901-AAA111", 0, null);
        booking.applyStatus(BookingStatus.SEARCHING_FAILED);
        authenticateAs(CUSTOMER, "CUSTOMER");

        getDetail(booking.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking.getId().toString()))
                .andExpect(jsonPath("$.referenceNumber").value("HFX-20260901-AAA111"))
                // The tracking screen learns the search failed from this.
                .andExpect(jsonPath("$.status").value("SEARCHING_FAILED"))
                .andExpect(jsonPath("$.serviceName").value("Tap repair"))
                .andExpect(jsonPath("$.date").value("2026-09-01T08:00:00Z"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.emergency").value(false))
                .andExpect(jsonPath("$.createdAt").value("2026-09-01T08:00:00Z"))
                .andExpect(jsonPath("$.subcategoryId").value(PLUMBING.toString()))
                // Unknown values are omitted, never invented.
                .andExpect(jsonPath("$.scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.providerId").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.coordinates").doesNotExist())
                .andExpect(jsonPath("$.netDurationSeconds").doesNotExist())
                .andExpect(jsonPath("$.photos").isEmpty())
                .andExpect(jsonPath("$.parts").isEmpty())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.invoice").doesNotExist());
    }

    @Test
    void detailUsesTheFinalTotalOnceSet() throws Exception {
        Booking booking = place("HFX-1", 0, T0.plus(Duration.ofDays(1)));
        booking.setFinalTotal(new BigDecimal("180.50"));
        authenticateAs(CUSTOMER, "CUSTOMER");

        getDetail(booking.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(180.50))
                .andExpect(jsonPath("$.scheduledAt").value("2026-09-02T08:00:00Z"))
                .andExpect(jsonPath("$.date").value("2026-09-02T08:00:00Z"));
    }

    @Test
    void detailAcceptsTheBookingReference() throws Exception {
        Booking booking = place("HFX-20260901-AAA111", 0, null);
        authenticateAs(CUSTOMER, "CUSTOMER");

        getDetail("HFX-20260901-AAA111")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking.getId().toString()));
    }

    @Test
    void assignedProviderSeesTheBooking() throws Exception {
        Booking booking = place("HFX-1", 0, null);
        booking.setProviderId(PROVIDER);
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getDetail(booking.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()));
    }

    @Test
    void assignedProviderSeesWhereTheJobIsAndWhatHasBeenRecorded() throws Exception {
        Booking booking = place("HFX-1", 0, null);
        booking.setProviderId(PROVIDER);
        booking.applyStatus(BookingStatus.JOB_STARTED);
        savedAddresses.put(booking.getAddressId(), new ServiceAddress("12 MG Road, Ara, 802301", 25.556, 84.66));
        JobMedia before = JobMedia.of(booking.getId(), "BEFORE_PHOTO", "image/jpeg", 10, "k1");
        JobMedia customerUpload = JobMedia.of(booking.getId(), "CUSTOMER_MEDIA", "image/jpeg", 10, "k0");
        when(media.findByBookingId(booking.getId())).thenReturn(List.of(customerUpload, before));
        PartsLineItem tap = PartsLineItem.of(booking.getId(), "Tap washer", 2, new BigDecimal("15.00"), T0);
        when(parts.findByBookingIdOrderByAddedAtAsc(booking.getId())).thenReturn(List.of(tap));
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getDetail(booking.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("12 MG Road, Ara, 802301"))
                .andExpect(jsonPath("$.coordinates.latitude").value(25.556))
                .andExpect(jsonPath("$.coordinates.longitude").value(84.66))
                // Only the provider's before/after photos gate start and complete.
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.photos[0].id").value(before.getId().toString()))
                .andExpect(jsonPath("$.photos[0].kind").value("BEFORE"))
                .andExpect(jsonPath("$.parts[0].itemName").value("Tap washer"))
                .andExpect(jsonPath("$.parts[0].quantity").value(2))
                .andExpect(jsonPath("$.parts[0].unitCost").value(15.00));
    }

    @Test
    void staffSeeAnyBooking() throws Exception {
        Booking booking = place("HFX-1", 0, null);
        authenticateAs(STRANGER, "SUPPORT_AGENT");

        getDetail(booking.getId()).andExpect(status().isOk());
    }

    @Test
    void strangerGets404IndistinguishableFromAMissingBooking() throws Exception {
        Booking booking = place("HFX-1", 0, null);
        authenticateAs(STRANGER, "CUSTOMER");

        getDetail(booking.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
        getDetail("HFX-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
        getDetail(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void providerNotAssignedToTheBookingGets404() throws Exception {
        Booking booking = place("HFX-1", 0, null);
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getDetail(booking.getId()).andExpect(status().isNotFound());
    }
}
