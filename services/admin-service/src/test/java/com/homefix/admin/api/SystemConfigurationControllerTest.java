package com.homefix.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AdminAction;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.support.InMemoryAuditLogStore;
import com.homefix.admin.support.InMemorySystemSettingStore;
import com.homefix.admin.sysconfig.SystemConfigService;

/**
 * Web-layer test for System Configuration (Requirement 19.2, 19.6, 19.7, 19.8): an ADMIN principal
 * receives 403 while a SUPER_ADMIN succeeds; responses are the portal's {@code SystemSetting[]};
 * a batch is validated per type and applied all-or-nothing; every change is audited. Uses
 * standalone MockMvc with the shared error envelope advice; the {@code Authentication} is supplied
 * via the request principal.
 */
class SystemConfigurationControllerTest {

    private static final String SUPER_ADMIN_ID = "44444444-4444-4444-4444-444444444444";

    private MockMvc mockMvc;
    private InMemoryAuditLogStore auditStore;
    private InMemorySystemSettingStore settingStore;

    private static Authentication auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken(SUPER_ADMIN_ID, null, authorities);
    }

    @BeforeEach
    void setUp() {
        auditStore = new InMemoryAuditLogStore();
        settingStore = new InMemorySystemSettingStore();
        AuditLogService auditLog = new AuditLogService(auditStore, new ObjectMapper(), Clock.systemUTC());
        SystemConfigurationController controller = new SystemConfigurationController(
                new SystemConfigService(settingStore, auditLog, Clock.systemUTC()), new AdminAuthorization());
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
        mockMvc.perform(put("/admin/system-config")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":{\"audit.pageSize\":\"100\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MODULE_ACCESS_DENIED"));
        mockMvc.perform(put("/admin/system-config/audit.pageSize")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"100\"}"))
                .andExpect(status().isForbidden());
        assertThat(settingStore.storedValues()).isEmpty();
    }

    @Test
    void superAdminReadsEverySettingWithItsDefault() throws Exception {
        mockMvc.perform(get("/admin/system-config").principal(auth("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("audit.pageSize"))
                .andExpect(jsonPath("$[0].label").value("Audit log page size"))
                .andExpect(jsonPath("$[0].description").isString())
                .andExpect(jsonPath("$[0].type").value("NUMBER"))
                .andExpect(jsonPath("$[0].value").value("50"))
                .andExpect(jsonPath("$[1].key").value("audit.summaryShowsValues"))
                .andExpect(jsonPath("$[1].type").value("BOOLEAN"))
                .andExpect(jsonPath("$[1].value").value("true"));
    }

    @Test
    void superAdminBatchUpdateIsPersistedNormalisedAndAuditedPerSetting() throws Exception {
        mockMvc.perform(put("/admin/system-config")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":{\"audit.pageSize\":\" 0100 \",\"audit.summaryShowsValues\":\"FALSE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("100"))
                .andExpect(jsonPath("$[1].value").value("false"));

        assertThat(settingStore.storedValues())
                .containsEntry("audit.pageSize", "100")
                .containsEntry("audit.summaryShowsValues", "false");
        assertThat(settingStore.updatedBy.get("audit.pageSize")).isEqualTo(UUID.fromString(SUPER_ADMIN_ID));
        assertThat(auditStore.all()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.getActionType()).isEqualTo(AdminAction.UPDATE);
            assertThat(entry.getEntityType()).isEqualTo("SYSTEM_CONFIG");
            assertThat(entry.getActorId()).isEqualTo(UUID.fromString(SUPER_ADMIN_ID));
        });
        assertThat(auditStore.all().get(0).getEntityId()).isEqualTo("audit.pageSize");
        assertThat(auditStore.all().get(0).getBeforeValues()).isEqualTo("{\"audit.pageSize\":\"50\"}");
        assertThat(auditStore.all().get(0).getAfterValues()).isEqualTo("{\"audit.pageSize\":\"100\"}");
    }

    @Test
    void anInvalidValueRejectsTheWholeBatch() throws Exception {
        mockMvc.perform(put("/admin/system-config")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":{\"audit.summaryShowsValues\":\"false\",\"audit.pageSize\":\"5000\","
                                + "\"maintenanceMode\":\"on\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SYSTEM_CONFIG"))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[0]").value("audit.pageSize: must be a whole number from 10 to 200"))
                .andExpect(jsonPath("$.details[1]").value("maintenanceMode: unknown setting"));

        assertThat(settingStore.storedValues()).isEmpty();
        assertThat(auditStore.all()).isEmpty();
    }

    @Test
    void singleSettingUpdateStillWorks() throws Exception {
        mockMvc.perform(put("/admin/system-config/audit.pageSize")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"25\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("audit.pageSize"))
                .andExpect(jsonPath("$[0].value").value("25"));
        assertThat(auditStore.all()).hasSize(1);

        mockMvc.perform(put("/admin/system-config/audit.pageSize")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SYSTEM_CONFIG"));
    }

    @Test
    void aMissingUpdatesObjectIs400() throws Exception {
        mockMvc.perform(put("/admin/system-config")
                        .principal(auth("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SYSTEM_CONFIG"));
    }
}
