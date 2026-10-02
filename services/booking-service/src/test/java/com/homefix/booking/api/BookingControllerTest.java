package com.homefix.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.BookingService;
import com.homefix.booking.service.CreateBookingCommand;

/**
 * Web-layer tests for {@link BookingController} (Requirements 7, 8.1, 9.16-9.18) using a
 * standalone MockMvc. The authenticated customer is taken from the JWT-populated
 * {@link Authentication}; the controller must delegate create/confirm/cancel to the
 * {@link BookingService} and map results (and the shared error envelope) correctly.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    private static final UUID CUSTOMER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID SUB = UUID.randomUUID();
    private static final UUID ADDRESS = UUID.randomUUID();

    @Mock private BookingService bookingService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BookingController(bookingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication customerAuth() {
        return new UsernamePasswordAuthenticationToken(CUSTOMER.toString(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private static Booking booking(BookingStatus status) {
        Booking b = Booking.create("HFX-100", CUSTOMER, CATEGORY, SUB, UUID.randomUUID(), false,
                null, new BigDecimal("100.00"));
        b.applyStatus(status);
        return b;
    }

    @Test
    void createScheduledDelegatesAndReturns201WithEstimate() throws Exception {
        Booking b = booking(BookingStatus.CREATED);
        PriceEstimate estimate = new PriceEstimate(new BigDecimal("120.00"),
                Map.of("basePrice", new BigDecimal("100.00")));
        when(bookingService.createScheduled(any(CreateBookingCommand.class)))
                .thenReturn(new BookingService.BookingCreationResult(b, estimate));

        String body = "{\"categoryId\":\"" + CATEGORY + "\",\"subcategoryId\":\"" + SUB
                + "\",\"addressId\":\"" + ADDRESS + "\",\"emergency\":false"
                + ",\"couponCode\":\"SAVE10\"}";

        mvc.perform(post("/bookings").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("HFX-100"))
                .andExpect(jsonPath("$.estimate.total").value(120.00));

        ArgumentCaptor<CreateBookingCommand> captor = ArgumentCaptor.forClass(CreateBookingCommand.class);
        verify(bookingService).createScheduled(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().customerId()).isEqualTo(CUSTOMER);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().emergency()).isFalse();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().addressId()).isEqualTo(ADDRESS);
        // The coupon the estimate was shown with must reach pricing (Requirement 6.10).
        org.assertj.core.api.Assertions.assertThat(captor.getValue().couponCode()).isEqualTo("SAVE10");
    }

    @Test
    void createEmergencyRoutesToEmergencyCreation() throws Exception {
        Booking b = booking(BookingStatus.SEARCHING_PROVIDER);
        when(bookingService.createEmergency(any(CreateBookingCommand.class)))
                .thenReturn(new BookingService.BookingCreationResult(b, null));

        String body = "{\"categoryId\":\"" + CATEGORY + "\",\"subcategoryId\":\"" + SUB
                + "\",\"addressId\":\"" + ADDRESS + "\",\"emergency\":true}";

        mvc.perform(post("/bookings").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SEARCHING_PROVIDER"));

        verify(bookingService).createEmergency(any(CreateBookingCommand.class));
    }

    @Test
    void createRejectsMissingRequiredFieldsWith400() throws Exception {
        mvc.perform(post("/bookings").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsMissingAddressWith400() throws Exception {
        // Dispatch resolves the service location only through the saved address; a booking
        // without one could never be matched, so it must not be created at all.
        String body = "{\"categoryId\":\"" + CATEGORY + "\",\"subcategoryId\":\"" + SUB
                + "\",\"emergency\":true}";

        mvc.perform(post("/bookings").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(bookingService);
    }

    @Test
    void createRejectsOverlongCouponWith400() throws Exception {
        String body = "{\"categoryId\":\"" + CATEGORY + "\",\"subcategoryId\":\"" + SUB
                + "\",\"addressId\":\"" + ADDRESS + "\",\"emergency\":true"
                + ",\"couponCode\":\"" + "X".repeat(33) + "\"}";

        mvc.perform(post("/bookings").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(bookingService);
    }

    @Test
    void confirmDelegatesToService() throws Exception {
        when(bookingService.confirm(any(String.class), any(Actor.class)))
                .thenReturn(booking(BookingStatus.SEARCHING_PROVIDER));

        mvc.perform(post("/bookings/HFX-100/confirmation").principal(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEARCHING_PROVIDER"));

        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(bookingService).confirm(org.mockito.ArgumentMatchers.eq("HFX-100"), actor.capture());
        org.assertj.core.api.Assertions.assertThat(actor.getValue().role()).isEqualTo("CUSTOMER");
        org.assertj.core.api.Assertions.assertThat(actor.getValue().id()).isEqualTo(CUSTOMER);
    }

    @Test
    void cancelPassesReasonToService() throws Exception {
        when(bookingService.cancel(any(String.class), any(Actor.class), any()))
                .thenReturn(booking(BookingStatus.CANCELLED));

        mvc.perform(post("/bookings/HFX-100/cancellation").principal(customerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(bookingService).cancel(org.mockito.ArgumentMatchers.eq("HFX-100"),
                any(Actor.class), org.mockito.ArgumentMatchers.eq("changed mind"));
    }

    @Test
    void cancelWithoutBodyPassesNullReason() throws Exception {
        when(bookingService.cancel(any(String.class), any(Actor.class), any()))
                .thenReturn(booking(BookingStatus.CANCELLED));

        mvc.perform(post("/bookings/HFX-100/cancellation").principal(customerAuth()))
                .andExpect(status().isOk());

        verify(bookingService).cancel(org.mockito.ArgumentMatchers.eq("HFX-100"),
                any(Actor.class), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void createWithMediaParsesPartsAndFilesIntoCommand() throws Exception {
        Booking b = booking(BookingStatus.CREATED);
        when(bookingService.createScheduled(any(CreateBookingCommand.class)))
                .thenReturn(new BookingService.BookingCreationResult(b, null));

        org.springframework.mock.web.MockMultipartFile media =
                new org.springframework.mock.web.MockMultipartFile("media", "photo.jpg",
                        "image/jpeg", new byte[]{1, 2, 3});

        mvc.perform(multipart("/bookings/media").file(media)
                        .param("categoryId", CATEGORY.toString())
                        .param("subcategoryId", SUB.toString())
                        .param("addressId", ADDRESS.toString())
                        .param("emergency", "false")
                        .param("scheduledAt", "2024-06-15T10:00:00Z")
                        .param("description", "leaky tap")
                        .param("couponCode", "SAVE10")
                        .principal(customerAuth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("HFX-100"));

        ArgumentCaptor<CreateBookingCommand> cmd = ArgumentCaptor.forClass(CreateBookingCommand.class);
        verify(bookingService).createScheduled(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().media()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().customerId()).isEqualTo(CUSTOMER);
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().scheduledAt())
                .isEqualTo(java.time.Instant.parse("2024-06-15T10:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().addressId()).isEqualTo(ADDRESS);
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().couponCode()).isEqualTo("SAVE10");
    }

    @Test
    void createWithMediaRejectsMissingAddressWith400() throws Exception {
        mvc.perform(multipart("/bookings/media")
                        .param("categoryId", CATEGORY.toString())
                        .param("subcategoryId", SUB.toString())
                        .param("emergency", "true")
                        .principal(customerAuth()))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(bookingService);
    }

    @Test
    void notFoundFromServiceMapsTo404() throws Exception {
        when(bookingService.confirm(any(String.class), any(Actor.class)))
                .thenThrow(BookingException.notFound("HFX-404"));

        mvc.perform(post("/bookings/HFX-404/confirmation").principal(customerAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }
}


