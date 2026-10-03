package com.homefix.provider.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;
import com.homefix.provider.service.TenantViews.TeamProviderView;
import com.homefix.provider.service.TenantViews.TenantView;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the Tenant Portal endpoints through the real security chain: only
 * {@code TENANT_ADMIN} reaches them (a Platform_Admin too is refused — {@code /tenant/**} means the
 * caller's own Tenant), the Tenant is resolved from the token's subject and never from the request
 * (Requirement MT-10.1, Property MT5), and a suspended Tenant answers 403 {@code TENANT_SUSPENDED}
 * (Requirement MT-1.4).
 */
@WebMvcTest(controllers = TenantPortalController.class, properties = {
        "homefix.security.jwt-secret=" + TenantPortalControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, ProviderRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class TenantPortalControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID tenantAdmin = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantTeamService teamService;

    private String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private Tenant tenant() {
        return Tenant.create(new TenantDetails("Ara Home Services", null, null, 25.556, 84.6603,
                new BigDecimal("20.0"), Set.of(UUID.randomUUID())), UUID.randomUUID());
    }

    @Test
    void me_returnsTheCallersTenant() throws Exception {
        Tenant t = tenant();
        when(tenantService.requirePortalTenant(tenantAdmin)).thenReturn(t);
        when(tenantService.get(t.getId())).thenReturn(new TenantView(t, 3, 1));

        mockMvc.perform(get("/tenant/me").header(HttpHeaders.AUTHORIZATION, token(tenantAdmin, "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(t.getId().toString()))
                .andExpect(jsonPath("$.providerCount").value(3));
    }

    @Test
    void providers_isTheCallersTeamOnly_whateverTheQueryString() throws Exception {
        Tenant mine = tenant();
        UUID provider = UUID.randomUUID();
        when(tenantService.requirePortalTenant(tenantAdmin)).thenReturn(mine);
        when(teamService.team(mine.getId())).thenReturn(List.of(new TeamProviderView(provider, "Ravi", null,
                "plumbing", "APPROVED", new BigDecimal("4.5"), true, true)));

        mockMvc.perform(get("/tenant/providers").param("tenantId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, token(tenantAdmin, "TENANT_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].providerId").value(provider.toString()))
                .andExpect(jsonPath("$[0].availableNow").value(true))
                .andExpect(jsonPath("$[0].assignable").value(true));

        verify(teamService).team(mine.getId());
    }

    @Test
    void addAndRemove_actOnTheCallersTenant() throws Exception {
        Tenant mine = tenant();
        UUID provider = UUID.randomUUID();
        when(tenantService.requirePortalTenant(tenantAdmin)).thenReturn(mine);
        when(teamService.addProvider(mine.getId(), "+919000000011", tenantAdmin)).thenReturn(new TeamProviderView(
                provider, "Ravi", "+919000000011", "plumbing", null, BigDecimal.ZERO, true, false));

        mockMvc.perform(post("/tenant/providers").header(HttpHeaders.AUTHORIZATION, token(tenantAdmin, "TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000011\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(provider.toString()));
        mockMvc.perform(delete("/tenant/providers/" + provider)
                        .header(HttpHeaders.AUTHORIZATION, token(tenantAdmin, "TENANT_ADMIN")))
                .andExpect(status().isNoContent());

        verify(teamService).removeProvider(mine.getId(), provider, tenantAdmin);
    }

    @Test
    void anotherTenantsProviderIs404() throws Exception {
        Tenant mine = tenant();
        UUID theirs = UUID.randomUUID();
        when(tenantService.requirePortalTenant(tenantAdmin)).thenReturn(mine);
        org.mockito.Mockito.doThrow(new ProviderException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "not on team"))
                .when(teamService).removeProvider(mine.getId(), theirs, tenantAdmin);

        mockMvc.perform(delete("/tenant/providers/" + theirs)
                        .header(HttpHeaders.AUTHORIZATION, token(tenantAdmin, "TENANT_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void suspendedTenantIs403OnEveryAction() throws Exception {
        when(tenantService.requirePortalTenant(tenantAdmin)).thenThrow(new ProviderException(
                HttpStatus.FORBIDDEN, "TENANT_SUSPENDED", "Your Tenant is suspended"));
        String auth = token(tenantAdmin, "TENANT_ADMIN");

        mockMvc.perform(get("/tenant/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
        mockMvc.perform(get("/tenant/providers").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
        mockMvc.perform(post("/tenant/providers").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000011\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));
        mockMvc.perform(delete("/tenant/providers/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("TENANT_SUSPENDED"));

        verifyNoInteractions(teamService);
    }

    @Test
    void otherRolesAreRefusedBeforeTheService() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN", "SERVICE_PROVIDER", "CUSTOMER", "DISPATCHER")) {
            String auth = token(tenantAdmin, role);
            mockMvc.perform(get("/tenant/me").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/tenant/providers").header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/tenant/providers").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mobileNumber\":\"+919000000011\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/tenant/providers/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/tenant/me")).andExpect(status().isUnauthorized());

        verifyNoInteractions(tenantService, teamService);
    }
}
