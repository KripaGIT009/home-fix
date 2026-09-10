package com.homefix.pricing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.pricing.api.dto.OverrideRequestDto;
import com.homefix.pricing.domain.Money;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;
import com.homefix.pricing.service.PriceQuoteService;
import com.homefix.pricing.service.PriceRequest;
import com.homefix.pricing.service.PricingConfigService;
import com.homefix.pricing.service.ProviderOverrideService;

/**
 * Web-layer tests for {@link PricingController} using a standalone MockMvc so the estimate and
 * override endpoints are exercised end-to-end through JSON (de)serialization and delegation to
 * the services (Requirement 6.9, 6.12), with the shared {@link GlobalExceptionHandler} wired in
 * for error mapping.
 */
@ExtendWith(MockitoExtension.class)
class PricingControllerTest {

    private static final UUID SUB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PriceQuoteService quoteService;
    @Mock private ProviderOverrideService overrideService;
    @Mock private PricingConfigService configService;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        PricingController controller = new PricingController(quoteService, overrideService, configService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PriceBreakdown breakdown() {
        return PriceBreakdown.builder()
                .basePrice(new BigDecimal("100.00"))
                .platformFee(new BigDecimal("10.00"))
                .taxes(new BigDecimal("5.00"))
                .build(new Money(2, RoundingMode.HALF_UP), new BigDecimal("0.01"));
    }

    @Test
    void estimateReturnsItemisedBreakdown() throws Exception {
        when(quoteService.quote(any(PriceRequest.class))).thenReturn(breakdown());

        String body = "{\"subcategoryId\":\"" + SUB + "\",\"emergency\":false,\"surgeActive\":false}";

        mvc.perform(post("/pricing/estimate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(100.00))
                .andExpect(jsonPath("$.platformFee").value(10.00))
                .andExpect(jsonPath("$.total").value(115.00));

        ArgumentCaptor<PriceRequest> captor = ArgumentCaptor.forClass(PriceRequest.class);
        verify(quoteService).quote(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().subcategoryId()).isEqualTo(SUB);
    }

    @Test
    void estimateRejectsMissingSubcategoryWith400() throws Exception {
        mvc.perform(post("/pricing/estimate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(quoteService);
    }

    @Test
    void validateOverrideResolvesParametersAndValidates() throws Exception {
        PricingParameters params = new PricingParameters(SUB, new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2.0"), new BigDecimal("2.0"),
                new BigDecimal("10.00"), new BigDecimal("500.00"));
        when(configService.requireParameters(SUB)).thenReturn(params);

        OverrideRequestDto req = new OverrideRequestDto(SUB, new BigDecimal("120.00"));

        mvc.perform(post("/pricing/overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedPrice").value(120.00));

        verify(configService).requireParameters(SUB);
        verify(overrideService).validateOverride(new BigDecimal("120.00"), params);
    }
}
