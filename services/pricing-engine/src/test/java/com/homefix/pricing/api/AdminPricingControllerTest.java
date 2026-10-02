package com.homefix.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    // ------------------------------------------------------------------ Admin Portal /config

    @Test
    void listConfigsReturnsThePortalShape() throws Exception {
        when(configService.listParameters()).thenReturn(List.of(params()));

        mvc.perform(get("/admin/pricing/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subcategoryId").value(SUB.toString()))
                .andExpect(jsonPath("$[0].subcategoryName").isEmpty())
                .andExpect(jsonPath("$[0].categoryName").isEmpty())
                .andExpect(jsonPath("$[0].basePrice").value(100.00))
                .andExpect(jsonPath("$[0].perKmRate").value(2.00))
                .andExpect(jsonPath("$[0].maxTravelCharge").value(50.00))
                .andExpect(jsonPath("$[0].platformFeePercent").value(10))
                .andExpect(jsonPath("$[0].nightSurcharge").value(20.00))
                .andExpect(jsonPath("$[0].weekendSurcharge").value(30.00))
                .andExpect(jsonPath("$[0].emergencyMultiplierCap").value(2.0))
                .andExpect(jsonPath("$[0].surgeMultiplierCap").value(2.0))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                // Backend-only fields stay off the portal contract.
                .andExpect(jsonPath("$[0].taxRate").doesNotExist())
                .andExpect(jsonPath("$[0].platformFeeRate").doesNotExist());
    }

    @Test
    void updateConfigMergesThePortalPayloadAsAFraction() throws Exception {
        when(configService.mergeParameters(any(PricingParameters.class))).thenReturn(params());
        String body = "{\"subcategoryId\":\"" + SUB + "\",\"subcategoryName\":null,"
                + "\"categoryName\":null,\"basePrice\":120,\"perKmRate\":3,"
                + "\"maxTravelCharge\":60,\"platformFeePercent\":12.5,\"nightSurcharge\":25,"
                + "\"weekendSurcharge\":35,\"emergencyMultiplierCap\":1.8,"
                + "\"surgeMultiplierCap\":1.4,\"currency\":\"INR\"}";

        mvc.perform(put("/admin/pricing/config/{id}", SUB).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subcategoryId").value(SUB.toString()))
                .andExpect(jsonPath("$.platformFeePercent").value(10))
                .andExpect(jsonPath("$.currency").value("INR"));

        ArgumentCaptor<PricingParameters> changes = ArgumentCaptor.forClass(PricingParameters.class);
        verify(configService).mergeParameters(changes.capture());
        assertThat(changes.getValue().subcategoryId()).isEqualTo(SUB);
        assertThat(changes.getValue().basePrice()).isEqualByComparingTo("120");
        assertThat(changes.getValue().platformFeeRate()).isEqualByComparingTo("0.125");
        assertThat(changes.getValue().emergencyMultiplier()).isEqualByComparingTo("1.8");
        assertThat(changes.getValue().taxRate()).isNull();
        assertThat(changes.getValue().overrideFloor()).isNull();
    }

    @Test
    void updateConfigRejectsABodyForAnotherSubcategory() throws Exception {
        String body = "{\"subcategoryId\":\"" + UUID.randomUUID() + "\",\"basePrice\":120}";

        mvc.perform(put("/admin/pricing/config/{id}", SUB).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verify(configService, never()).mergeParameters(any());
    }

    @Test
    void updateConfigSurfacesTheMergeValidationError() throws Exception {
        when(configService.mergeParameters(any(PricingParameters.class)))
                .thenThrow(PricingException.validation("basePrice is required"));

        mvc.perform(put("/admin/pricing/config/{id}", SUB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perKmRate\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}
