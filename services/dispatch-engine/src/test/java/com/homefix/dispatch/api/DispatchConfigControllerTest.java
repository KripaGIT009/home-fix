package com.homefix.dispatch.api;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.service.DispatchSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Admin Portal dispatch-rules endpoint returns the portal's {@code DispatchConfig}
 * shape and applies or rejects an update as a whole (Requirements 8.2, 8.4, 8.5, 8.8, 19.5). Uses a
 * standalone MockMvc over the real store and properties so no Kafka/Redis context is required;
 * the RBAC rules are covered in {@code DispatchRbacConfigTest}.
 */
class DispatchConfigControllerTest {

    private MatchingWeightsStore store;
    private DispatchProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new MatchingWeightsStore();
        properties = new DispatchProperties();
        DispatchConfigController controller =
                new DispatchConfigController(new DispatchSettingsService(store, properties));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(controller) // the @ExceptionHandlers live on the controller
                .build();
    }

    private static String body(String weights, String radius, String increment, String cycles, String timeout) {
        return "{\"weights\":" + weights + ",\"initialRadiusKm\":" + radius
                + ",\"radiusIncrementKm\":" + increment + ",\"maxExpansionCycles\":" + cycles
                + ",\"offerTimeoutSeconds\":" + timeout + "}";
    }

    private static final String VALID_WEIGHTS =
            "{\"distance\":0.40,\"availability\":0.20,\"rating\":0.20,\"skill\":0.10,\"performance\":0.10}";

    @Test
    void getReturnsThePortalShape() throws Exception {
        mockMvc.perform(get("/admin/dispatch/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weights.distance").value(0.30))
                .andExpect(jsonPath("$.weights.availability").value(0.25))
                .andExpect(jsonPath("$.weights.rating").value(0.20))
                .andExpect(jsonPath("$.weights.skill").value(0.15))
                .andExpect(jsonPath("$.weights.performance").value(0.10))
                .andExpect(jsonPath("$.initialRadiusKm").value(10.0))
                .andExpect(jsonPath("$.radiusIncrementKm").value(5.0))
                .andExpect(jsonPath("$.maxExpansionCycles").value(3))
                .andExpect(jsonPath("$.offerTimeoutSeconds").value(60));
    }

    @Test
    void putAppliesEverySettingAndReturnsTheNewConfig() throws Exception {
        mockMvc.perform(put("/admin/dispatch/config").contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_WEIGHTS, "8", "4", "2", "45")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weights.distance").value(0.40))
                .andExpect(jsonPath("$.initialRadiusKm").value(8.0))
                .andExpect(jsonPath("$.offerTimeoutSeconds").value(45));

        assertThat(store.current()).isEqualTo(MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10));
        assertThat(properties.getInitialRadiusKm()).isEqualTo(8.0);
        assertThat(properties.getRadiusIncrementKm()).isEqualTo(4.0);
        assertThat(properties.getMaxExpansionCycles()).isEqualTo(2);
        assertThat(properties.getOfferTimeoutSeconds()).isEqualTo(45L);
    }

    @Test
    void putRejectsWeightsThatDoNotSumToOneAndChangesNothing() throws Exception {
        String badWeights =
                "{\"distance\":0.50,\"availability\":0.20,\"rating\":0.20,\"skill\":0.10,\"performance\":0.10}";

        mockMvc.perform(put("/admin/dispatch/config").contentType(MediaType.APPLICATION_JSON)
                        .content(body(badWeights, "8", "4", "2", "45")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_WEIGHTS"))
                .andExpect(jsonPath("$.message").value(containsString("sum to exactly 1.0")));

        assertThat(store.current()).isEqualTo(MatchingWeights.DEFAULT);
        assertThat(properties.getInitialRadiusKm()).isEqualTo(10.0);
    }

    @Test
    void putRejectsAnOutOfRangeTimeoutAndChangesNothing() throws Exception {
        mockMvc.perform(put("/admin/dispatch/config").contentType(MediaType.APPLICATION_JSON)
                        .content(body(VALID_WEIGHTS, "8", "4", "2", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_SETTINGS"))
                .andExpect(jsonPath("$.message").value(containsString("offerTimeoutSeconds")));

        assertThat(store.current()).isEqualTo(MatchingWeights.DEFAULT);
        assertThat(properties.getOfferTimeoutSeconds()).isEqualTo(60L);
    }

    @Test
    void putRejectsAMissingFieldWithTheErrorEnvelope() throws Exception {
        mockMvc.perform(put("/admin/dispatch/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weights\":" + VALID_WEIGHTS + ",\"initialRadiusKm\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}
