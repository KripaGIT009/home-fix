package com.homefix.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.dispatch.DispatchWeights;
import com.homefix.admin.dispatch.DispatchWeightsStore;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.support.InMemoryAuditLogStore;

/**
 * Web-layer test for the dispatch weight update endpoint (Requirement 19.5, Property 19): a valid
 * set is accepted and audited; an invalid set is rejected with a descriptive 400 and leaves the
 * active weights unchanged.
 */
class DispatchRuleControllerTest {

    private MockMvc mockMvc;
    private DispatchWeightsStore store;
    private InMemoryAuditLogStore auditStore;

    private static Authentication superAdmin() {
        return new UsernamePasswordAuthenticationToken("55555555-5555-5555-5555-555555555555",
                null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    @BeforeEach
    void setUp() {
        store = new DispatchWeightsStore();
        auditStore = new InMemoryAuditLogStore();
        AuditLogService auditLog = new AuditLogService(auditStore, new ObjectMapper(), Clock.systemUTC());
        DispatchRuleController controller = new DispatchRuleController(
                store, new AdminAuthorization(), auditLog);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validWeightsAreAcceptedAndAudited() throws Exception {
        String body = """
                {"distanceWeight":0.30,"availabilityWeight":0.25,"ratingWeight":0.20,
                 "skillWeight":0.15,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights").principal(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceWeight").value(0.30));

        assertThat(auditStore.all()).hasSize(1);
    }

    @Test
    void weightsNotSummingToOneRejectedWith400AndUnchanged() throws Exception {
        DispatchWeights original = store.current();
        String body = """
                {"distanceWeight":0.50,"availabilityWeight":0.25,"ratingWeight":0.20,
                 "skillWeight":0.15,"performanceWeight":0.10}""";

        mockMvc.perform(put("/admin/dispatch/weights").principal(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_WEIGHTS"));

        assertThat(store.current()).isEqualTo(original);
        assertThat(auditStore.all()).isEmpty();
    }

    @Test
    void outOfRangeWeightRejectedWith400() throws Exception {
        String body = """
                {"distanceWeight":1.50,"availabilityWeight":0.0,"ratingWeight":0.0,
                 "skillWeight":0.0,"performanceWeight":0.0}""";

        mockMvc.perform(put("/admin/dispatch/weights").principal(superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DISPATCH_WEIGHTS"));
    }
}
