package com.homefix.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.BookingQueryService.BookingView;
import com.homefix.booking.service.TenantBookingService;
import com.homefix.booking.service.TenantBookingService.TenantBookingView;
import com.homefix.booking.support.Bookings;

/**
 * Web-layer tests for {@link TenantBookingController} (Requirements MT-5.1, MT-8.3): the
 * {@code TenantBooking} JSON the Tenant Portal reads, the caller's id (never a request parameter)
 * reaching the service, and the 409/404 envelopes. Role gating is {@code BookingRbacConfigTest}'s;
 * the rules themselves are {@code TenantBookingServiceTest}'s.
 */
class TenantBookingControllerTest {

    private static final UUID ADMIN = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

    private TenantBookingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TenantBookingService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new TenantBookingController(service, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ADMIN.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private TenantBookingView queuedRow() {
        Booking booking = Bookings.placed(UUID.randomUUID(), UUID.randomUUID(), "HFX-20261003-QUE001",
                T0, T0.plusSeconds(7200));
        booking.applyStatus(BookingStatus.AWAITING_ASSIGNMENT);
        booking.setQueuedForAssignmentAt(T0.plusSeconds(600));
        ReflectionTestUtils.setField(booking, "emergency", true);
        return new TenantBookingView(new BookingView(booking, "Tap repair"),
                new ServiceAddress("12 Station Rd, Ara", 25.556, 84.663));
    }

    @Test
    void queueReturnsTheTenantBookingShapeForTheCaller() throws Exception {
        TenantBookingView row = queuedRow();
        when(service.queue(ADMIN)).thenReturn(List.of(row));

        mvc.perform(get("/tenant/bookings/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(row.view().booking().getId().toString()))
                .andExpect(jsonPath("$[0].reference").value("HFX-20261003-QUE001"))
                .andExpect(jsonPath("$[0].serviceName").value("Tap repair"))
                .andExpect(jsonPath("$[0].status").value("AWAITING_ASSIGNMENT"))
                .andExpect(jsonPath("$[0].isEmergency").value(true))
                .andExpect(jsonPath("$[0].scheduledAt").value("2026-10-03T11:00:00Z"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-03T09:00:00Z"))
                .andExpect(jsonPath("$[0].queuedAt").value("2026-10-03T09:10:00Z"))
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].address").value("12 Station Rd, Ara"))
                .andExpect(jsonPath("$[0].coordinates.latitude").value(25.556))
                .andExpect(jsonPath("$[0].coordinates.longitude").value(84.663))
                .andExpect(jsonPath("$[0].providerId").value(Matchers.nullValue()));
    }

    @Test
    void listPassesTheStatusFilter() throws Exception {
        when(service.bookings(ADMIN, BookingStatus.PROVIDER_ASSIGNED)).thenReturn(List.of());

        mvc.perform(get("/tenant/bookings").param("status", "PROVIDER_ASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(service).bookings(ADMIN, BookingStatus.PROVIDER_ASSIGNED);
    }

    @Test
    void anUnknownStatusIs400() throws Exception {
        mvc.perform(get("/tenant/bookings").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void assignPassesTheCallerBookingAndProvider() throws Exception {
        UUID provider = UUID.randomUUID();
        TenantBookingView row = queuedRow();
        when(service.assign(eq(ADMIN), eq("HFX-20261003-QUE001"), eq(provider))).thenReturn(row);

        mvc.perform(post("/tenant/bookings/HFX-20261003-QUE001/assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + provider + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("HFX-20261003-QUE001"));
    }

    @Test
    void aLostRaceIs409BookingNotAssignable() throws Exception {
        when(service.assign(any(), any(), any()))
                .thenThrow(BookingException.notAssignable("just assigned by someone else"));

        mvc.perform(post("/tenant/bookings/HFX-1/assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_ASSIGNABLE"));
    }

    @Test
    void aMissingProviderIdIs400() throws Exception {
        mvc.perform(post("/tenant/bookings/HFX-1/assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
}
