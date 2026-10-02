package com.homefix.provider.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
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
import com.homefix.provider.domain.ProviderAdminRow;
import com.homefix.provider.service.ProviderAdminService;
import com.homefix.provider.service.ProviderAdminService.AdminProviderView;
import com.homefix.provider.service.ProviderException;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the Admin Portal's provider management endpoints through the service's real
 * security chain ({@link WebSecurityConfig}, the shared JWT and RBAC filters and the rules from
 * {@link ProviderRbacConfig}): the response is exactly the portal's {@code AdminProvider} shape,
 * statuses are mapped onto the portal's enums, the acting Admin is forwarded, and a non-admin token
 * is refused before the service is touched.
 */
@WebMvcTest(controllers = AdminProviderController.class, properties = {
        "homefix.security.jwt-secret=" + AdminProviderControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, ProviderRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class AdminProviderControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID admin = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderAdminService adminService;

    private String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private AdminProviderView view(UUID id, boolean known, String rawStatus) {
        return new AdminProviderView(new ProviderAdminRow(id, "Ravi Kumar", new BigDecimal("4.60")),
                "plumbing", known, rawStatus);
    }

    // ------------------------------------------------------------------ list

    @Test
    void list_returnsThePortalShape() throws Exception {
        when(adminService.list(null)).thenReturn(List.of(view(provider, true, "APPROVED")));

        mockMvc.perform(get("/admin/providers").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(provider.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].primarySkill").value("plumbing"))
                .andExpect(jsonPath("$[0].rating").value(4.60))
                .andExpect(jsonPath("$[0].verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                // Not owned by this service: present and null.
                .andExpect(jsonPath("$[0].mobileNumber").isEmpty())
                .andExpect(jsonPath("$[0].completedJobs").isEmpty())
                .andExpect(jsonPath("$[0].isOnline").isEmpty())
                .andExpect(jsonPath("$[0].online").doesNotExist());
    }

    @Test
    void list_mapsVerificationOntoThePortalEnums() throws Exception {
        UUID suspended = UUID.randomUUID();
        UUID inReview = UUID.randomUUID();
        UUID neverSubmitted = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(adminService.list("ravi")).thenReturn(List.of(
                view(suspended, true, "SUSPENDED"),
                view(inReview, true, "BACKGROUND_CHECK_PENDING"),
                view(neverSubmitted, true, null),
                view(unknown, false, null)));

        mockMvc.perform(get("/admin/providers").param("search", "ravi")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verificationStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$[1].verificationStatus").value("DOCUMENT_SUBMITTED"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[2].verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$[2].status").value("ACTIVE"))
                // Verification Service unavailable: unknown, never guessed.
                .andExpect(jsonPath("$[3].verificationStatus").isEmpty())
                .andExpect(jsonPath("$[3].status").isEmpty());
    }

    @Test
    void list_isRefusedForNonAdminRoles() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER")) {
            mockMvc.perform(get("/admin/providers").header(HttpHeaders.AUTHORIZATION, token(admin, role)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/providers")).andExpect(status().isUnauthorized());

        verifyNoInteractions(adminService);
    }

    // ------------------------------------------------------------------ status change

    @Test
    void changeStatus_forwardsTheActingAdminAndReturnsTheUpdatedProvider() throws Exception {
        when(adminService.changeStatus(provider, "SUSPENDED", admin)).thenReturn(view(provider, true, "SUSPENDED"));

        mockMvc.perform(patch("/admin/providers/" + provider + "/status")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.verificationStatus").value("SUSPENDED"));

        verify(adminService).changeStatus(provider, "SUSPENDED", admin);
    }

    @Test
    void changeStatus_surfacesTheServicesRefusal() throws Exception {
        when(adminService.changeStatus(provider, "DEACTIVATED", admin))
                .thenThrow(new ProviderException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROVIDER_STATUS",
                        "DEACTIVATED is not supported"));
        when(adminService.changeStatus(provider, "ACTIVE", admin))
                .thenThrow(new ProviderException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "Only a SUSPENDED provider can be reinstated"));

        mockMvc.perform(patch("/admin/providers/" + provider + "/status")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEACTIVATED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PROVIDER_STATUS"));
        mockMvc.perform(patch("/admin/providers/" + provider + "/status")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void changeStatus_withoutStatusOrBody_is400InTheSharedEnvelope() throws Exception {
        for (String body : List.of("{}", "not json")) {
            mockMvc.perform(patch("/admin/providers/" + provider + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        verify(adminService, never()).changeStatus(any(), anyString(), any());
    }

    @Test
    void changeStatus_isRefusedForAProviderEvenOnTheirOwnRecord() throws Exception {
        mockMvc.perform(patch("/admin/providers/" + provider + "/status")
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }
}
