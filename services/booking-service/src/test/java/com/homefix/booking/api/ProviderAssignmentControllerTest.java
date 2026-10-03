package com.homefix.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingException;
import com.homefix.booking.service.InvalidTransitionException;
import com.homefix.booking.service.ProviderAssignmentService;
import com.homefix.booking.support.Bookings;

/**
 * Web-layer tests for {@link ProviderAssignmentController} (Requirement MT-6): the caller's id from
 * the token is the Provider whose answer is recorded, and a stranger's 404 and a wrong state's 409
 * reach the client in the service's envelope.
 */
class ProviderAssignmentControllerTest {

    private static final UUID PROVIDER = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private ProviderAssignmentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ProviderAssignmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProviderAssignmentController(service, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                PROVIDER.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE_PROVIDER"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptanceAnswersTheAcceptedBooking() throws Exception {
        Booking booking = Bookings.inState(BookingStatus.PROVIDER_ACCEPTED);
        when(service.accept("HFX-1", PROVIDER)).thenReturn(booking);

        mvc.perform(post("/bookings/HFX-1/assignment/acceptance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_ACCEPTED"))
                .andExpect(jsonPath("$.bookingId").value(booking.getId().toString()));
    }

    @Test
    void rejectionAnswersTheRequeuedBooking() throws Exception {
        when(service.decline("HFX-1", PROVIDER)).thenReturn(Bookings.inState(BookingStatus.AWAITING_ASSIGNMENT));

        mvc.perform(post("/bookings/HFX-1/assignment/rejection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_ASSIGNMENT"));
    }

    @Test
    void aStrangerGets404() throws Exception {
        when(service.accept(any(), any())).thenThrow(BookingException.notFound("HFX-1"));

        mvc.perform(post("/bookings/HFX-1/assignment/acceptance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void aWrongStateIs409() throws Exception {
        when(service.decline(any(), any())).thenThrow(new InvalidTransitionException(UUID.randomUUID(),
                BookingStatus.PROVIDER_ACCEPTED, BookingStatus.AWAITING_ASSIGNMENT));

        mvc.perform(post("/bookings/HFX-1/assignment/rejection"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_TRANSITION"));
    }
}
