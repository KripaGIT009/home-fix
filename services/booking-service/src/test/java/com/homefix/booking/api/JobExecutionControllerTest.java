package com.homefix.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.Actor;
import com.homefix.booking.service.AddPartsCommand;
import com.homefix.booking.service.JobExecutionService;
import com.homefix.booking.service.MediaService;

/**
 * Web-layer tests for {@link JobExecutionController} (Task 15; Requirements 9.3-9.11,
 * 11.2-11.6). Each milestone endpoint must delegate to {@link JobExecutionService} with the
 * provider actor derived from the authentication, and the photo endpoint must resolve the
 * booking and store the media via {@link MediaService}.
 */
@ExtendWith(MockitoExtension.class)
class JobExecutionControllerTest {

    private static final UUID PROVIDER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private JobExecutionService jobExecution;
    @Mock private MediaService mediaService;
    @Mock private BookingRepository bookingRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new JobExecutionController(jobExecution, mediaService, bookingRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication providerAuth() {
        return new UsernamePasswordAuthenticationToken(PROVIDER.toString(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SERVICE_PROVIDER")));
    }

    private static Booking booking(BookingStatus status) {
        Booking b = Booking.create("HFX-200", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("100.00"));
        b.applyStatus(status);
        return b;
    }

    @Test
    void onTheWayDelegatesWithProviderActor() throws Exception {
        when(jobExecution.markOnTheWay(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.PROVIDER_ON_THE_WAY));

        mvc.perform(post("/bookings/HFX-200/on-the-way").principal(providerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_ON_THE_WAY"));

        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(jobExecution).markOnTheWay(eq("HFX-200"), actor.capture());
        org.assertj.core.api.Assertions.assertThat(actor.getValue().role()).isEqualTo("SERVICE_PROVIDER");
        org.assertj.core.api.Assertions.assertThat(actor.getValue().id()).isEqualTo(PROVIDER);
    }

    @Test
    void arrivedDelegates() throws Exception {
        when(jobExecution.markArrived(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.PROVIDER_ARRIVED));

        mvc.perform(post("/bookings/HFX-200/arrived").principal(providerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_ARRIVED"));
    }

    @Test
    void startDelegates() throws Exception {
        when(jobExecution.startJob(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.JOB_STARTED));

        mvc.perform(post("/bookings/HFX-200/start").principal(providerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JOB_STARTED"));
    }

    @Test
    void pauseRequiresReasonAndDelegates() throws Exception {
        when(jobExecution.pauseJob(eq("HFX-200"), any(Actor.class), eq("waiting for parts")))
                .thenReturn(booking(BookingStatus.JOB_PAUSED));

        mvc.perform(post("/bookings/HFX-200/pause").principal(providerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"waiting for parts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JOB_PAUSED"));

        verify(jobExecution).pauseJob(eq("HFX-200"), any(Actor.class), eq("waiting for parts"));
    }

    @Test
    void pauseRejectsBlankReasonWith400() throws Exception {
        mvc.perform(post("/bookings/HFX-200/pause").principal(providerAuth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void resumeDelegates() throws Exception {
        when(jobExecution.resumeJob(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.JOB_STARTED));

        mvc.perform(post("/bookings/HFX-200/resume").principal(providerAuth()))
                .andExpect(status().isOk());

        verify(jobExecution).resumeJob(eq("HFX-200"), any(Actor.class));
    }

    @Test
    void addPartsMapsRequestToCommand() throws Exception {
        when(jobExecution.addParts(eq("HFX-200"), any(Actor.class), any(AddPartsCommand.class)))
                .thenReturn(booking(BookingStatus.ADDITIONAL_QUOTE_REQUIRED));

        mvc.perform(post("/bookings/HFX-200/parts").principal(providerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemName\":\"Valve\",\"quantity\":2,\"unitCost\":12.50}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AddPartsCommand> cmd = ArgumentCaptor.forClass(AddPartsCommand.class);
        verify(jobExecution).addParts(eq("HFX-200"), any(Actor.class), cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().itemName()).isEqualTo("Valve");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().quantity()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().unitCost()).isEqualByComparingTo("12.50");
    }

    @Test
    void addPartsRejectsInvalidQuantityWith400() throws Exception {
        mvc.perform(post("/bookings/HFX-200/parts").principal(providerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemName\":\"Valve\",\"quantity\":0,\"unitCost\":12.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void approveQuoteDelegates() throws Exception {
        when(jobExecution.approveAdditionalQuote(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.JOB_STARTED));

        mvc.perform(post("/bookings/HFX-200/quote/approval").principal(providerAuth()))
                .andExpect(status().isOk());

        verify(jobExecution).approveAdditionalQuote(eq("HFX-200"), any(Actor.class));
    }

    @Test
    void rejectQuoteDelegates() throws Exception {
        when(jobExecution.rejectAdditionalQuote(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.JOB_STARTED));

        mvc.perform(post("/bookings/HFX-200/quote/rejection").principal(providerAuth()))
                .andExpect(status().isOk());

        verify(jobExecution).rejectAdditionalQuote(eq("HFX-200"), any(Actor.class));
    }

    @Test
    void completeDelegates() throws Exception {
        when(jobExecution.completeJob(eq("HFX-200"), any(Actor.class)))
                .thenReturn(booking(BookingStatus.JOB_COMPLETED));

        mvc.perform(post("/bookings/HFX-200/complete").principal(providerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JOB_COMPLETED"));
    }

    @Test
    void attachPhotoResolvesBookingAndStoresMedia() throws Exception {
        Booking b = booking(BookingStatus.PROVIDER_ARRIVED);
        when(bookingRepository.findByReference("HFX-200")).thenReturn(Optional.of(b));

        MockMultipartFile file = new MockMultipartFile("file", "before.jpg", "image/jpeg",
                new byte[]{1, 2, 3});

        mvc.perform(multipart("/bookings/HFX-200/photos").file(file).param("type", "before_photo")
                        .principal(providerAuth()))
                .andExpect(status().isNoContent());

        verify(mediaService).attach(eq(b.getId()), eq("BEFORE_PHOTO"), anyList());
    }

    @Test
    void attachPhotoRejectsUnknownTypeWith422() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});

        mvc.perform(multipart("/bookings/HFX-200/photos").file(file).param("type", "SIDE_PHOTO")
                        .principal(providerAuth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}

