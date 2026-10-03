package com.homefix.provider.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.provider.config.InternalApiKeyFilter;
import com.homefix.provider.config.ProviderRbacConfig;
import com.homefix.provider.config.WebSecurityConfig;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.domain.Tenant.TenantDetails;
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;
import com.homefix.provider.service.TenantViews.CoveringTenant;
import com.homefix.provider.service.TenantViews.MembershipView;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for booking-service's Tenant lookups under {@code /internal/tenants} through the
 * real security chain: the response shapes of the design, 404 as an answer with a stable error
 * code, and only the shared {@code X-Internal-Api-Key} admitted — no key, a wrong key, and an
 * end-user token (even a Platform_Admin's) are refused (Requirement MT-10.5).
 */
@WebMvcTest(controllers = InternalTenantController.class, properties = {
        "homefix.security.jwt-secret=" + InternalTenantControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=" + InternalTenantControllerTest.INTERNAL_KEY
})
@Import({WebSecurityConfig.class, ProviderRbacConfig.class, GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalTenantControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantTeamService teamService;

    private final Tenant tenant = Tenant.create(new TenantDetails("Ara Home Services", null, null, 25.556,
            84.6603, new BigDecimal("20.0"), Set.of(UUID.randomUUID())), UUID.randomUUID());

    @Test
    void covering_returnsTheTenantsNearestFirst() throws Exception {
        UUID category = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(tenantService.covering(25.556, 84.6603, category)).thenReturn(List.of(
                new CoveringTenant(tenant.getId(), "Ara Home Services", 0.0),
                new CoveringTenant(second, "Patna Fixers", 12.5)));

        mockMvc.perform(get("/internal/tenants/covering").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("lat", "25.556").param("lon", "84.6603").param("categoryId", category.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants.length()").value(2))
                .andExpect(jsonPath("$.tenants[0].tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.tenants[0].name").value("Ara Home Services"))
                .andExpect(jsonPath("$.tenants[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$.tenants[1].distanceKm").value(12.5));
    }

    @Test
    void covering_withoutACategoryIs400() throws Exception {
        mockMvc.perform(get("/internal/tenants/covering").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("lat", "25.556").param("lon", "84.6603"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void byAdmin_ofProvider_andById_answerOr404() throws Exception {
        UUID adminUser = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(tenantService.byAdmin(adminUser)).thenReturn(Optional.of(tenant));
        when(tenantService.byAdmin(provider)).thenReturn(Optional.empty());
        when(tenantService.ofProvider(provider)).thenReturn(Optional.of(tenant));
        when(tenantService.ofProvider(adminUser)).thenReturn(Optional.empty());
        when(tenantService.find(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantService.find(provider)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/tenants/by-admin/" + adminUser).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.name").value("Ara Home Services"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/internal/tenants/by-admin/" + provider).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_FOUND"));

        mockMvc.perform(get("/internal/tenants/of-provider/" + provider).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.name").value("Ara Home Services"))
                .andExpect(jsonPath("$.status").doesNotExist());
        mockMvc.perform(get("/internal/tenants/of-provider/" + adminUser).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_FOUND"));

        mockMvc.perform(get("/internal/tenants/" + tenant.getId()).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/internal/tenants/" + provider).header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_FOUND"));
    }

    @Test
    void membership_answersOr404() throws Exception {
        UUID member = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(teamService.membership(tenant.getId(), member)).thenReturn(Optional.of(new MembershipView(true, true, "APPROVED")));
        when(teamService.membership(tenant.getId(), stranger)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/tenants/" + tenant.getId() + "/providers/" + member)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(true))
                .andExpect(jsonPath("$.assignable").value(true))
                .andExpect(jsonPath("$.verificationStatus").value("APPROVED"));
        mockMvc.perform(get("/internal/tenants/" + tenant.getId() + "/providers/" + stranger)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void onlyTheServiceCredentialIsAdmitted() throws Exception {
        String adminToken = "Bearer " + Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "TENANT_ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        String path = "/internal/tenants/" + tenant.getId();

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(InternalApiKeyFilter.HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(tenantService, teamService);
    }
}
