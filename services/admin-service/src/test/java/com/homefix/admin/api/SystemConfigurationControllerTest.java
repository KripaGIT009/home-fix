package com.homefix.admin.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.support.InMemoryAuditLogStore;
import com.homefix.admin.sysconfig.SystemConfigStore;

/**
 * Web-layer test proving an ADMIN principal receives 403 on the System Configuration module while
 * a SUPER_ADMIN succeeds (Requirement 19.6, 19.7). Uses standalone MockMvc with the shared error
 * envelope advice; the {@code Authentication} is supplied via the request principal.
 */
class SystemConfigurationControllerTest {

    private MockMvc mockMvc;
    private InMemoryAuditLogStore auditStore;

    private static Authentication auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("44444444-4444-4444-4444-444444444444",
                null, authorities);
    }

    @BeforeEach
    void setUp() {
        auditStore = new InMemoryAuditLogStore();
        AuditLogService auditLog = new AuditLogService(auditStore, new ObjectMapper(), Clock.systemUTC());
        SystemConfigurationController controller = new SystemConfigurationController(
                new SystemConfigStore(), new AdminAuthorization(), auditLog);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void adminReceives403OnSystemConfiguration() throws Exception {
        mockMvc.perform(get("/admin/system-config").principal(auth("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MODULE_ACCESS_DENIED"));
    }

    @Test
    void adminReceives403OnSystemConfigurationUpdate() throws Exception {
        mockMvc.perform(put("/admin/system-config/maintenanceMode")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"on\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MODULE_ACCESS_DENIED"));
    }

    @Test
    void superAdminCanReadSystemConfiguration() throws Exception {
        mockMvc.perform(get("/admin/system-config").principal(auth("SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminCanUpdateSystemConfiguration() throws Exception {
        mockMvc.perform(put("/admin/system-config/maintenanceMode")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"on\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceMode").value("on"));
    }
}
