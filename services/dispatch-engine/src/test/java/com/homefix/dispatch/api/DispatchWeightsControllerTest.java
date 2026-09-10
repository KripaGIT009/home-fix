package com.homefix.dispatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Admin weight-update endpoint accepts a valid weight set and rejects an invalid one
 * with a descriptive 400, leaving the active weights unchanged (Requirement 19.5, Property 19).
 * Uses a standalone MockMvc so no database/Kafka/Redis context is required.
 */
class DispatchWeightsControllerTest {

    private MatchingWeightsStore store;
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = new MatchingWeightsStore();
        DispatchWeightsController controller = new DispatchWeightsController(store);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(controller) // the @ExceptionHandler lives on the controller
                .build();
    }

    @Test
    void acceptsWeightsThatSumToOne() throws Exception {
        String body = """
                {"distanceWeight":0.40,"availabilityWeight":0.20,"ratingWeight":0.20,
                 "skillWeight":0.10,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceWeight").value(0.40));

        assertThat(store.current().distanceWeight()).isEqualTo(0.40);
    }

    @Test
    void rejectsWeightsThatDoNotSumToOneAndLeavesExistingUnchanged() throws Exception {
        MatchingWeights before = store.current();
        String body = """
                {"distanceWeight":0.40,"availabilityWeight":0.40,"ratingWeight":0.20,
                 "skillWeight":0.10,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_WEIGHTS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sum to exactly 1.0")));

        assertThat(store.current()).isEqualTo(before);
    }

    @Test
    void rejectsWeightOutOfRange() throws Exception {
        String body = """
                {"distanceWeight":1.20,"availabilityWeight":-0.10,"ratingWeight":-0.10,
                 "skillWeight":0.0,"performanceWeight":0.0}""";

        mockMvc.perform(put("/admin/dispatch/weights")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[0.0, 1.0]")));
    }
}
