package com.homefix.provider.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.service.ProviderService;

/**
 * Web-layer ownership tests for {@link ProviderController}.
 *
 * <p>The shared {@code RbacEnforcementFilter} can only assert that a caller holds
 * {@code ROLE_SERVICE_PROVIDER}; it cannot tell <em>which</em> provider they are. These tests cover
 * the second half of the decision, {@link CallerIdentity#requireSelfOrStaff(UUID)}: provider A must
 * be refused (403) on provider B's paths, allowed on their own, and a staff principal must be
 * allowed on anybody's.
 *
 * <p>The controller is driven through standalone MockMvc with the real
 * {@link GlobalExceptionHandler} installed, so the assertions also prove the refusal surfaces as
 * the shared {@code ErrorResponseDto} envelope rather than an empty 403.
 */
class ProviderControllerOwnershipTest {

    private ProviderService providerService;
    private MockMvc mvc;

    private final UUID providerA = UUID.randomUUID();
    private final UUID providerB = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        providerService = mock(ProviderService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ProviderController(providerService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(UUID subject, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                subject.toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private ProviderProfile profile(UUID id) {
        return ProviderProfile.createWithId(id);
    }

    // ------------------------------------------------------------------ cross-provider refusals

    @Test
    void providerReadingAnotherProvidersProfileIsForbidden() throws Exception {
        authenticate(providerA, "SERVICE_PROVIDER");

        mvc.perform(get("/providers/{id}/profile", providerB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(providerService, never()).getProfile(providerB);
    }

    @Test
    void providerUpdatingAnotherProvidersRadiusIsForbidden() throws Exception {
        authenticate(providerA, "SERVICE_PROVIDER");

        mvc.perform(put("/providers/{id}/radius", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceRadiusKm\":15}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        verify(providerService, never()).updateServiceRadius(eq(providerB), anyInt());
    }

    // ------------------------------------------------------------------ self-service succeeds

    @Test
    void providerReadingTheirOwnProfileSucceeds() throws Exception {
        authenticate(providerA, "SERVICE_PROVIDER");
        when(providerService.getProfile(providerA)).thenReturn(profile(providerA));

        mvc.perform(get("/providers/{id}/profile", providerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(providerA.toString()));

        verify(providerService).getProfile(providerA);
    }

    @Test
    void providerUpdatingTheirOwnRadiusSucceeds() throws Exception {
        authenticate(providerA, "SERVICE_PROVIDER");
        when(providerService.updateServiceRadius(providerA, 15)).thenReturn(profile(providerA));

        mvc.perform(put("/providers/{id}/radius", providerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceRadiusKm\":15}"))
                .andExpect(status().isOk());

        verify(providerService).updateServiceRadius(providerA, 15);
    }

    // ------------------------------------------------------------------ staff act for anybody

    @Test
    void adminReadingAnyProvidersProfileSucceeds() throws Exception {
        authenticate(admin, "ADMIN");
        when(providerService.getProfile(providerB)).thenReturn(profile(providerB));

        mvc.perform(get("/providers/{id}/profile", providerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(providerB.toString()));

        verify(providerService).getProfile(providerB);
    }

    @Test
    void adminUpdatingAnyProvidersRadiusSucceeds() throws Exception {
        authenticate(admin, "ADMIN");
        when(providerService.updateServiceRadius(providerB, 20)).thenReturn(profile(providerB));

        mvc.perform(put("/providers/{id}/radius", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceRadiusKm\":20}"))
                .andExpect(status().isOk());

        verify(providerService).updateServiceRadius(providerB, 20);
    }

    @Test
    void supportAgentMayReadAnyProvidersProfile() throws Exception {
        authenticate(UUID.randomUUID(), "SUPPORT_AGENT");
        when(providerService.getProfile(providerB)).thenReturn(profile(providerB));

        mvc.perform(get("/providers/{id}/profile", providerB))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ no principal at all

    @Test
    void unauthenticatedCallerIsUnauthorized() throws Exception {
        mvc.perform(get("/providers/{id}/profile", providerA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        verify(providerService, never()).getProfile(providerA);
    }

    @Test
    void nonUuidPrincipalIsRejected() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "not-a-uuid", "n/a", AuthorityUtils.createAuthorityList("ROLE_SERVICE_PROVIDER")));

        mvc.perform(get("/providers/{id}/profile", providerA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRINCIPAL"));
    }

    // ------------------------------------------------------------------ every handler is guarded

    @Test
    void everyHandlerRefusesACrossProviderCaller() throws Exception {
        authenticate(providerA, "SERVICE_PROVIDER");

        // Bodies must be structurally valid: Spring resolves and bean-validates @RequestBody before
        // the handler runs, so an invalid payload would surface a 400 and mask the ownership check.
        mvc.perform(put("/providers/{id}/profile", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\",\"categories\":[],\"skillTags\":[\"tiling\"],"
                                + "\"yearsExperience\":1,\"serviceRadiusKm\":10}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/providers/{id}/availability", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[]}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/providers/{id}/emergency-availability", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emergencyAvailable\":true}"))
                .andExpect(status().isForbidden());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/providers/{id}/settlements", providerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"bankAccountRef\":\"ref-1\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/providers/{id}/earnings", providerB))
                .andExpect(status().isForbidden());
    }
}
