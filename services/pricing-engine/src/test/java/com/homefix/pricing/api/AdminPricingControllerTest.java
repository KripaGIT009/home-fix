package com.homefix.pricing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.pricing.api.dto.PricingParametersDto;
import com.homefix.pricing.domain.PricingParameters;
import com.homefix.pricing.service.PricingConfigService;
import com.homefix.pricing.service.PricingException;

/**
 * Web-layer tests for {@link AdminPricingController} (Requirement 6.11): reading and updating
 * per-subcategory pricing parameters, and the 404 mapping when parameters are missing.
 */
@ExtendWith(MockitoExtension.class)
class AdminPricingControllerTest {

    private static final UUID SUB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PricingConfigService configService;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AdminPricingController controller = new AdminPricingController(configService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PricingParameters params() {
        return new PricingParameters(SUB, new BigDecimal("100.00"), new BigDecimal("2.00"),
                new BigDecimal("50.00"), new BigDecimal("20.00"), new BigDecimal("30.00"),
                new BigDecimal("0.10"), new BigDecimal("0.05"), new BigDecimal("2.0"),
                new BigDecimal("2.0"), new BigDecimal("10.00"), new BigDecimal("500.00"));
    }

    @Test
    void getParametersReturnsConfiguredValues() throws Exception {
        when(configService.requireParameters(SUB)).thenReturn(params());

        mvc.perform(get("/admin/pricing/parameters/{id}", SUB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subcategoryId").value(SUB.toString()))
                .andExpect(jsonPath("$.basePrice").value(100.00));
    }

    @Test
    void getParametersReturns404WhenMissing() throws Exception {
        when(configService.requireParameters(SUB))
                .thenThrow(PricingException.parametersNotFound("none for " + SUB));

        mvc.perform(get("/admin/pricing/parameters/{id}", SUB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRICING_PARAMETERS_NOT_FOUND"));
    }

    @Test
    void updateParametersPersistsAndReturnsSaved() throws Exception {
        when(configService.updateParameters(any(PricingParameters.class))).thenReturn(params());

        PricingParametersDto dto = PricingParametersDto.from(params());

        mvc.perform(put("/admin/pricing/parameters").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(100.00));
    }

    @Test
    void updateParametersRejectsMissingRequiredFieldsWith400() throws Exception {
        // basePrice omitted -> @NotNull violation.
        String body = "{\"subcategoryId\":\"" + SUB + "\"}";

        mvc.perform(put("/admin/pricing/parameters").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void pricingExceptionStatusIsHonoured() {
        // Direct unit check that the exception maps to its declared status (defensive).
        PricingException ex = PricingException.parametersNotFound("x");
        org.assertj.core.api.Assertions.assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
