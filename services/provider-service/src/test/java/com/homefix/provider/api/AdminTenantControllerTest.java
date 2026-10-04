package com.homefix.provider.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.provider.config.ProviderRbacConfig;
import com.homefix.provider.config.WebSecurityConfig;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.Tenant.TenantDetails;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.service.TenantApplicationService;
import com.homefix.provider.service.TenantCommand;
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;
import com.homefix.provider.service.TenantViews.AdminView;
import com.homefix.provider.service.TenantViews.TeamProviderView;
import com.homefix.provider.service.TenantViews.TenantView;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the Admin Portal's Tenants module through the real security chain
 * ({@link WebSecurityConfig}, the shared JWT and RBAC filters and the rules from
 * {@link ProviderRbacConfig}): the {@code Tenant} / {@code TeamProvider} shapes, status codes,
 * the acting Admin forwarded, errors in the shared envelope, and every non-platform role —
 * including {@code TENANT_ADMIN} — refused before the service is touched (Requirement MT-10.3).
 */
@WebMvcTest(controllers = AdminTenantController.class, properties = {
        "homefix.security.jwt-secret=" + AdminTenantControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, ProviderRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class AdminTenantControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID admin = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantTeamService teamService;

    @MockBean
    private TenantApplicationService applications;

    static String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private TenantView view() {
        Tenant t = Tenant.create(new TenantDetails("Ara Home Services", "+919000000031", "ops@ara.example",
                25.556, 84.6603, new BigDecimal("20.0"), Set.of(category)), admin);
        return new TenantView(t, 2, 1);
    }

    private static final String BODY = """
            {"name":"Ara Home Services","contactPhone":"+919000000031","contactEmail":"ops@ara.example",
             "baseLatitude":25.556,"baseLongitude":84.6603,"serviceRadiusKm":20,
             "categoryIds":["%s"],"status":"SUSPENDED"}
            """;

    @Test
    void list_returnsTheTenantShape() throws Exception {
        TenantView v = view();
        when(tenantService.list()).thenReturn(List.of(v));

        mockMvc.perform(get("/admin/tenants").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(v.tenant().getId().toString()))
                .andExpect(jsonPath("$[0].name").value("Ara Home Services"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].contactPhone").value("+919000000031"))
                .andExpect(jsonPath("$[0].contactEmail").value("ops@ara.example"))
                .andExpect(jsonPath("$[0].baseLatitude").value(25.556))
                .andExpect(jsonPath("$[0].baseLongitude").value(84.6603))
                .andExpect(jsonPath("$[0].serviceRadiusKm").value(20.0))
                .andExpect(jsonPath("$[0].categoryIds[0]").value(category.toString()))
                .andExpect(jsonPath("$[0].providerCount").value(2))
                .andExpect(jsonPath("$[0].adminCount").value(1))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void list_canBeFilteredByStatus_forPendingApplications() throws Exception {
        TenantView active = view();
        Tenant pendingTenant = Tenant.apply(new TenantDetails("Patna Plumbers", null, null, 25.6, 85.1,
                new BigDecimal("10.0"), Set.of(category)), UUID.randomUUID());
        when(tenantService.list()).thenReturn(List.of(active, new TenantView(pendingTenant, 0, 0)));

        mockMvc.perform(get("/admin/tenants").param("status", "pending_approval")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$[0].applicantUserId").value(pendingTenant.getApplicantUserId().toString()));
    }

    @Test
    void approveAndReject_actAsTheCallingAdmin() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(applications.approve(tenantId, admin)).thenReturn(view());
        when(applications.reject(tenantId, "Outside our cities", admin)).thenReturn(view());

        mockMvc.perform(post("/admin/tenants/{id}/approval", tenantId)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/tenants/{id}/rejection", tenantId)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Outside our cities\"}"))
                .andExpect(status().isOk());
        verify(applications).approve(tenantId, admin);
        verify(applications).reject(tenantId, "Outside our cities", admin);
    }

    @Test
    void decisions_areRefusedToATenantAdmin() throws Exception {
        mockMvc.perform(post("/admin/tenants/{id}/approval", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "TENANT_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(applications);
    }

    @Test
    void create_is201AndForwardsTheBodyAndTheActingAdmin() throws Exception {
        when(tenantService.create(any(), eq(admin))).thenReturn(view());

        mockMvc.perform(post("/admin/tenants").header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(category)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ara Home Services"));

        ArgumentCaptor<TenantCommand> command = ArgumentCaptor.forClass(TenantCommand.class);
        verify(tenantService).create(command.capture(), eq(admin));
        org.assertj.core.api.Assertions.assertThat(command.getValue()).isEqualTo(new TenantCommand(
                "Ara Home Services", "+919000000031", "ops@ara.example", 25.556, 84.6603,
                new BigDecimal("20"), List.of(category), "SUSPENDED"));
    }

    @Test
    void update_validationRefusalNamesTheRuleInTheSharedEnvelope() throws Exception {
        UUID id = UUID.randomUUID();
        when(tenantService.update(eq(id), any(), eq(admin))).thenThrow(new ProviderException(
                HttpStatus.BAD_REQUEST, "INVALID_SERVICE_RADIUS", "serviceRadiusKm must be within [1, 100]"));

        mockMvc.perform(put("/admin/tenants/" + id).header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(category)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SERVICE_RADIUS"))
                .andExpect(jsonPath("$.message").value("serviceRadiusKm must be within [1, 100]"));
    }

    @Test
    void members_listsAdminsAndTeam() throws Exception {
        UUID id = UUID.randomUUID();
        UUID adminUser = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(tenantService.admins(id)).thenReturn(List.of(new AdminView(adminUser, "+919000000031")));
        when(teamService.team(id)).thenReturn(List.of(new TeamProviderView(provider, "Ravi", null, "plumbing",
                "APPROVED", new BigDecimal("4.50"), true, true)));

        mockMvc.perform(get("/admin/tenants/" + id + "/members").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admins[0].userId").value(adminUser.toString()))
                .andExpect(jsonPath("$.admins[0].mobileNumber").value("+919000000031"))
                .andExpect(jsonPath("$.providers[0].providerId").value(provider.toString()))
                .andExpect(jsonPath("$.providers[0].displayName").value("Ravi"))
                .andExpect(jsonPath("$.providers[0].mobileNumber").isEmpty())
                .andExpect(jsonPath("$.providers[0].primarySkill").value("plumbing"))
                .andExpect(jsonPath("$.providers[0].verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.providers[0].rating").value(4.50))
                .andExpect(jsonPath("$.providers[0].availableNow").value(true))
                .andExpect(jsonPath("$.providers[0].assignable").value(true));
    }

    @Test
    void addAdmin_is201_andItsRefusalsPassThrough() throws Exception {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(tenantService.addAdmin(id, "+919000000031", admin)).thenReturn(new AdminView(user, "+919000000031"));
        when(tenantService.addAdmin(id, "+919000000032", admin)).thenThrow(new ProviderException(
                HttpStatus.CONFLICT, "ADMIN_OF_OTHER_TENANT", "That user already administers another Tenant"));
        when(tenantService.addAdmin(id, "+919000000033", admin)).thenThrow(new ProviderException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account"));

        mockMvc.perform(post("/admin/tenants/" + id + "/admins").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000031\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andExpect(jsonPath("$.mobileNumber").value("+919000000031"));
        mockMvc.perform(post("/admin/tenants/" + id + "/admins").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000032\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_OF_OTHER_TENANT"));
        mockMvc.perform(post("/admin/tenants/" + id + "/admins").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000033\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
        mockMvc.perform(post("/admin/tenants/" + id + "/admins").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void removals_are204() throws Exception {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID provider = UUID.randomUUID();

        mockMvc.perform(delete("/admin/tenants/" + id + "/admins/" + user)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/admin/tenants/" + id + "/providers/" + provider)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isNoContent());

        verify(tenantService).removeAdmin(id, user, admin);
        verify(teamService).removeProvider(id, provider, admin);
    }

    @Test
    void addProvider_is201_andConflictPassesThrough() throws Exception {
        UUID id = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(teamService.addProvider(id, "+919000000011", admin)).thenReturn(new TeamProviderView(provider,
                "Ravi", "+919000000011", "plumbing", "APPROVED", BigDecimal.ZERO, false, true));
        when(teamService.addProvider(id, "+919000000012", admin)).thenThrow(new ProviderException(
                HttpStatus.CONFLICT, "PROVIDER_IN_OTHER_TENANT", "taken"));

        mockMvc.perform(post("/admin/tenants/" + id + "/providers").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000011\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(provider.toString()))
                .andExpect(jsonPath("$.mobileNumber").value("+919000000011"));
        mockMvc.perform(post("/admin/tenants/" + id + "/providers").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000012\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_IN_OTHER_TENANT"));
    }

    @Test
    void everyEndpointIsRefusedForNonPlatformRoles() throws Exception {
        UUID id = UUID.randomUUID();
        for (String role : List.of("TENANT_ADMIN", "SERVICE_PROVIDER", "CUSTOMER", "DISPATCHER",
                "SUPPORT_AGENT", "FINANCE_ADMIN")) {
            String auth = token(admin, role);
            mockMvc.perform(get("/admin/tenants").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/tenants").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(category)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/admin/tenants/" + id).header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(category)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/admin/tenants/" + id + "/admins/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/tenants")).andExpect(status().isUnauthorized());

        verifyNoInteractions(tenantService, teamService);
    }
}
