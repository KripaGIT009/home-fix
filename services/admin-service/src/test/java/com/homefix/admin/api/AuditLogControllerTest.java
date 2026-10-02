package com.homefix.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.admin.audit.AdminAction;
import com.homefix.admin.audit.AuditLogEntry;
import com.homefix.admin.audit.AuditLogQueryService;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.support.InMemoryAuditLogStore;
import com.homefix.admin.support.InMemorySystemSettingStore;
import com.homefix.admin.sysconfig.SystemConfigService;

/**
 * Web-layer test for the Audit Logs view (Requirement 19.8): the portal's {@code AuditLogPage}
 * shape, the optional action / entity-type filters, keyset paging with an opaque cursor, and the
 * existing by-entity / by-actor lookups still answering. Role gating at the filter is
 * {@code AdminRbacConfigTest}'s; the module check here admits ADMIN and refuses other roles.
 */
class AuditLogControllerTest {

    private static final UUID ACTOR = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant BASE = Instant.parse("2026-10-01T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryAuditLogStore auditStore;
    private InMemorySystemSettingStore settingStore;
    private MockMvc mockMvc;

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken(ACTOR.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @BeforeEach
    void setUp() {
        auditStore = new InMemoryAuditLogStore();
        settingStore = new InMemorySystemSettingStore();
        SystemConfigService systemConfig = new SystemConfigService(settingStore,
                new AuditLogService(auditStore, objectMapper, Clock.systemUTC()), Clock.systemUTC());
        AuditLogController controller = new AuditLogController(auditStore,
                new AuditLogQueryService(auditStore, systemConfig, objectMapper), new AdminAuthorization());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AuditLogEntry append(int minute, AdminAction action, String entityType, String before, String after) {
        return auditStore.append(new AuditLogEntry(UUID.randomUUID(), ACTOR, action, entityType, "id-" + minute,
                before, after, BASE.plusSeconds(60L * minute)));
    }

    @Test
    void returnsTheNewestEntriesInThePortalShape() throws Exception {
        append(1, AdminAction.CREATE, "COUPON", null, "{\"code\":\"SAVE10\"}");
        AuditLogEntry newest = append(2, AdminAction.UPDATE, "USER", "{\"status\":\"ACTIVE\"}",
                "{\"status\":\"SUSPENDED\"}");

        mockMvc.perform(get("/admin/audit-logs").principal(auth("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.entries[0].actorId").value(ACTOR.toString()))
                .andExpect(jsonPath("$.entries[0].actorName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.entries[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.entries[0].entityType").value("USER"))
                .andExpect(jsonPath("$.entries[0].entityId").value("id-2"))
                .andExpect(jsonPath("$.entries[0].changeSummary").value("status: ACTIVE → SUSPENDED"))
                .andExpect(jsonPath("$.entries[0].timestamp").value("2026-10-01T09:02:00Z"))
                .andExpect(jsonPath("$.entries[1].changeSummary").value("Created: code=SAVE10"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void filtersByActionAndEntityTypeIgnoringCase() throws Exception {
        append(1, AdminAction.CREATE, "COUPON", null, "{}");
        append(2, AdminAction.UPDATE, "COUPON", "{}", "{}");
        append(3, AdminAction.UPDATE, "SYSTEM_CONFIG", "{}", "{}");

        mockMvc.perform(get("/admin/audit-logs").param("action", "UPDATE").param("entityType", "coup")
                        .principal(auth("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].entityId").value("id-2"));
    }

    @Test
    void pagesThroughTheWholeLogWithTheCursor() throws Exception {
        settingStore.with("audit.pageSize", "10");
        for (int minute = 0; minute < 25; minute++) {
            append(minute, AdminAction.UPDATE, "USER", "{}", "{}");
        }
        // Two entries in the same instant: the id breaks the tie, so neither is skipped or repeated.
        auditStore.append(new AuditLogEntry(UUID.randomUUID(), ACTOR, AdminAction.DELETE, "USER", "tie",
                "{}", null, BASE.plusSeconds(60L * 7)));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/admin/audit-logs").principal(auth("ADMIN"));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String json = mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode page = objectMapper.readTree(json);
            page.get("entries").forEach(entry -> seen.add(entry.get("entityId").asText()));
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(26).doesNotHaveDuplicates();
        assertThat(seen.get(0)).isEqualTo("id-24");
        assertThat(seen.get(25)).isEqualTo("id-0");
    }

    @Test
    void anUnknownActionOrMalformedCursorIs400() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").param("action", "EXPLODE").principal(auth("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_QUERY"));
        mockMvc.perform(get("/admin/audit-logs").param("cursor", "not-a-cursor").principal(auth("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_QUERY"));
    }

    @Test
    void nonAdminRolesAreRefusedByTheModuleCheck() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").principal(auth("SUPPORT_AGENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MODULE_ACCESS_DENIED"));
    }

    @Test
    void entityAndActorLookupsStillReturnRawEntries() throws Exception {
        append(1, AdminAction.UPDATE, "USER", "{}", "{}");

        mockMvc.perform(get("/admin/audit-logs").param("entityType", "USER").param("entityId", "id-1")
                        .principal(auth("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actionType").value("UPDATE"));
        mockMvc.perform(get("/admin/audit-logs").param("actorId", ACTOR.toString()).principal(auth("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
