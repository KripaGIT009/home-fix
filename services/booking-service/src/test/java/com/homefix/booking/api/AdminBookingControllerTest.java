package com.homefix.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingQueryService;
import com.homefix.booking.service.BookingService;
import com.homefix.booking.service.InvalidTransitionException;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * Web-layer tests for {@link AdminBookingController} (Requirement 19.2): the JSON the Admin
 * Portal's {@code AdminBooking} type reads, the search and status filter, and the force-cancel's
 * delegation to {@link BookingService#cancel} under a staff {@link Actor}. Role gating belongs to
 * {@code BookingRbacConfig} and is covered by {@code BookingRbacConfigTest}.
 *
 * <p>The list runs over the real {@link BookingQueryService} and an in-memory repository; the
 * cancel's business rules belong to {@code BookingServiceTest}, so the service is mocked here.
 */
class AdminBookingControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STAFF = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID PLUMBING = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private InMemoryBookingRepository repository;
    private BookingService bookingService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        bookingService = mock(BookingService.class);
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
        BookingQueryService queryService = new BookingQueryService(repository, catalog,
                mock(JobMediaRepository.class), mock(PartsLineItemRepository.class), id -> Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminBookingController(queryService, bookingService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
        authenticateAs(STAFF, "SUPPORT_AGENT");
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

    private Booking place(String reference, long minutes) {
        return repository.save(Bookings.placed(CUSTOMER, PLUMBING, reference,
                T0.plus(Duration.ofMinutes(minutes)), T0.plus(Duration.ofDays(2))));
    }

    // ----- list --------------------------------------------------------------

    @Test
    void listReturnsTheAdminBookingShapeNewestFirst() throws Exception {
        Booking older = place("HFX-20261001-AAA111", 1);
        Booking newer = place("HFX-20261001-BBB222", 2);
        newer.setFinalTotal(new BigDecimal("180.50"));

        mvc.perform(get("/admin/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$[0].reference").value("HFX-20261001-BBB222"))
                .andExpect(jsonPath("$[0].serviceName").value("Tap repair"))
                .andExpect(jsonPath("$[0].status").value("CREATED"))
                .andExpect(jsonPath("$[0].isEmergency").value(false))
                .andExpect(jsonPath("$[0].emergency").doesNotExist())
                .andExpect(jsonPath("$[0].totalAmount").value(180.50))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-01T08:02:00Z"))
                .andExpect(jsonPath("$[0].scheduledAt").value("2026-09-03T08:00:00Z"))
                // Names are owned by other services: sent as null, never invented.
                .andExpect(jsonPath("$[0].customerName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].providerName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()))
                // No final total yet: the accepted estimate.
                .andExpect(jsonPath("$[1].totalAmount").value(100.00));
    }

    @Test
    void listFiltersBySearchAndStatus() throws Exception {
        Booking cancelled = place("HFX-20261001-ABC123", 1);
        cancelled.applyStatus(BookingStatus.CANCELLED);
        place("HFX-20261001-XYZ789", 2);

        mvc.perform(get("/admin/bookings").param("search", "abc"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value("HFX-20261001-ABC123"));
        mvc.perform(get("/admin/bookings").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
        mvc.perform(get("/admin/bookings").param("search", cancelled.getId().toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value("HFX-20261001-ABC123"));
        // The portal omits an empty filter, but an empty value must not fail either.
        mvc.perform(get("/admin/bookings").param("status", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unknownStatusIsAValidationError() throws Exception {
        mvc.perform(get("/admin/bookings").param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ----- cancel ------------------------------------------------------------

    @Test
    void cancelDelegatesWithAStaffActorAndReturnsTheRow() throws Exception {
        Booking booking = place("HFX-20261001-ABC123", 1);
        when(bookingService.cancel(eq(booking.getId().toString()), any(Actor.class), anyString()))
                .thenAnswer(inv -> {
                    booking.applyStatus(BookingStatus.CANCELLED);
                    return booking;
                });
        // A staff member who also books services: the staff role, not CUSTOMER, is the actor.
        authenticateAs(STAFF, "CUSTOMER", "SUPPORT_AGENT");

        mvc.perform(post("/admin/bookings/{id}/cancel", booking.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer unreachable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(booking.getId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.serviceName").value("Tap repair"))
                .andExpect(jsonPath("$.isEmergency").value(false));

        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(bookingService).cancel(eq(booking.getId().toString()), actor.capture(),
                eq("Customer unreachable"));
        assertThat(actor.getValue()).isEqualTo(Actor.user(STAFF, "SUPPORT_AGENT"));
    }

    @Test
    void cancelRequiresAReason() throws Exception {
        mvc.perform(post("/admin/bookings/{id}/cancel", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(bookingService);
    }

    @Test
    void cancelFromANonCancellableStateIsAConflict() throws Exception {
        when(bookingService.cancel(anyString(), any(Actor.class), anyString()))
                .thenThrow(new InvalidTransitionException(UUID.randomUUID(),
                        BookingStatus.PAYMENT_COMPLETED, BookingStatus.CANCELLED));

        mvc.perform(post("/admin/bookings/{id}/cancel", "HFX-20261001-ABC123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_TRANSITION"));
    }

    @Test
    void cancelByANonStaffCallerIsForbidden() throws Exception {
        // RBAC keeps customers off this path; the controller still refuses to act as one.
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(post("/admin/bookings/{id}/cancel", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bookingService);
    }
}
