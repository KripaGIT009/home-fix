package com.homefix.provider.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import com.homefix.provider.config.InternalApiKeyFilter;
import com.homefix.provider.config.WebSecurityConfig;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.service.ProviderService;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

/**
 * Web-layer tests for {@code POST /internal/providers/{id}/earnings} through the service's real
 * security chain: the Payment Service's credit reaches {@link ProviderService#creditJobEarning}
 * with the booking it is for, and only the shared {@code X-Internal-Api-Key} gets in.
 */
@WebMvcTest(controllers = InternalEarningController.class, properties = {
        "homefix.security.jwt-secret=" + InternalEarningControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=" + InternalEarningControllerTest.INTERNAL_KEY
})
@Import({WebSecurityConfig.class, GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalEarningControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOOKING = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String BODY = "{\"bookingId\":\"" + BOOKING + "\",\"bookingReference\":\"HFX-1\","
            + "\"gross\":615.25,\"platformFee\":123.05}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderService providerService;

    @Test
    void creditsTheBookingsEarningAndReturnsTheBalance() throws Exception {
        ProviderProfile profile = ProviderProfile.createWithId(PROVIDER);
        ReflectionTestUtils.setField(profile, "walletBalance", new BigDecimal("492.20"));
        when(providerService.creditJobEarning(eq(PROVIDER), eq(BOOKING), eq("HFX-1"),
                eq(new BigDecimal("615.25")), eq(new BigDecimal("123.05")))).thenReturn(profile);

        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.walletBalance").value(492.20));
    }

    @Test
    void unknownProviderIs404() throws Exception {
        when(providerService.creditJobEarning(any(), any(), any(), any(), any()))
                .thenThrow(ProviderException.notFound("Provider " + PROVIDER + " not found"));

        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    /** Review finding: the loser of two racing deliveries hit the unique index and answered 500. */
    @Test
    void aDeliveryThatLostTheRaceIsAnsweredAsAlreadyApplied() throws Exception {
        ProviderProfile profile = ProviderProfile.createWithId(PROVIDER);
        ReflectionTestUtils.setField(profile, "walletBalance", new BigDecimal("492.20"));
        when(providerService.creditJobEarning(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_provider_earning_job_credit_booking"));
        when(providerService.jobCreditAlreadyApplied(PROVIDER, BOOKING)).thenReturn(Optional.of(profile));

        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletBalance").value(492.20));
    }

    @Test
    void aBookingAlreadyCreditedToAnotherProviderIs409() throws Exception {
        when(providerService.creditJobEarning(any(), any(), any(), any(), any()))
                .thenThrow(new ProviderException(HttpStatus.CONFLICT, "EARNING_BOOKING_CONFLICT", "taken"));

        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EARNING_BOOKING_CONFLICT"));
    }

    @Test
    void missingBookingIdIs400() throws Exception {
        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gross\":10,\"platformFee\":1}"))
                .andExpect(status().isBadRequest());
        verify(providerService, never()).creditJobEarning(any(), any(), any(), any(), any());
    }

    @Test
    void withoutTheServiceCredentialNothingIsCredited() throws Exception {
        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/providers/{id}/earnings", PROVIDER)
                        .header(InternalApiKeyFilter.HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(providerService, never()).creditJobEarning(any(), any(), any(), any(), any());
    }
}
