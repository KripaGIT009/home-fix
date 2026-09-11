package com.homefix.verification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.homefix.verification.api.dto.AdminActionRequest;
import com.homefix.verification.api.dto.BackgroundCheckResultRequest;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.service.DocumentUpload;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.service.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

/**
 * Direct-invocation tests for the provider- and admin-facing verification controllers. Both carry
 * a {@code {providerId}} path variable, so they are exercised by direct method calls with the
 * acting admin placed on the {@link SecurityContextHolder}. Verifies delegation for each state
 * transition (Requirement 5.4-5.9), document upload assembly, and the auth guards.
 *
 * <p>The provider-facing {@code submitDocuments} and {@code get} now assert ownership through
 * {@link CallerIdentity}, so those tests authenticate an actual provider subject; the ownership
 * section at the bottom covers the cross-provider refusals and the staff override. The dispatch
 * gate {@code assertJobAssignmentEligible} is deliberately ownership-free and is therefore still
 * driven with no principal at all.
 */
class VerificationControllersTest {

    private VerificationService service;
    private AdminVerificationController adminController;
    private VerificationController providerController;
    private final UUID admin = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID otherProvider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(VerificationService.class);
        adminController = new AdminVerificationController(service);
        providerController = new VerificationController(service, new CallerIdentity());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAdmin(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    /** Authenticates a SERVICE_PROVIDER principal whose JWT subject is {@code id}. */
    private void authenticateProvider(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id.toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_SERVICE_PROVIDER")));
    }

    /** Authenticates a principal holding a single arbitrary role. */
    private void authenticateWithRole(UUID id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id.toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private Verification verification() {
        return Verification.create(provider);
    }

    @Test
    void markDocumentsVerified_delegatesWithActorAndReason() {
        authenticateAdmin(admin.toString());
        when(service.markDocumentsVerified(eq(provider), eq(admin), eq("looks good")))
                .thenReturn(verification());

        adminController.markDocumentsVerified(provider, new AdminActionRequest("looks good"));

        verify(service).markDocumentsVerified(provider, admin, "looks good");
    }

    @Test
    void completeBackgroundCheck_forwardsResult() {
        authenticateAdmin(admin.toString());
        when(service.completeBackgroundCheck(eq(provider), eq(admin), eq("CLEAR")))
                .thenReturn(verification());

        adminController.completeBackgroundCheck(provider, new BackgroundCheckResultRequest("CLEAR"));

        verify(service).completeBackgroundCheck(provider, admin, "CLEAR");
    }

    @Test
    void approveRejectSuspend_delegate() {
        authenticateAdmin(admin.toString());
        when(service.approve(any(), any(), any())).thenReturn(verification());
        when(service.reject(any(), any(), any())).thenReturn(verification());
        when(service.suspend(any(), any(), any())).thenReturn(verification());

        adminController.approve(provider, null);
        adminController.reject(provider, new AdminActionRequest("fraudulent docs"));
        adminController.suspend(provider, new AdminActionRequest("complaint upheld"));

        verify(service).approve(provider, admin, null);
        verify(service).reject(provider, admin, "fraudulent docs");
        verify(service).suspend(provider, admin, "complaint upheld");
    }

    @Test
    void adminAction_withoutAuthentication_isUnauthorized() {
        assertThatThrownBy(() -> adminController.approve(provider, null))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void adminAction_withNonUuidPrincipal_isRejected() {
        authenticateAdmin("not-a-uuid");
        assertThatThrownBy(() -> adminController.approve(provider, null))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("INVALID_PRINCIPAL"));
    }

    @Test
    void submitDocuments_readsMultipartAndDelegates() {
        authenticateProvider(provider);
        MultipartFile gov = new MockMultipartFile("GOVERNMENT_ID", "id.jpg", "image/jpeg",
                new byte[]{1, 2, 3});
        MultipartFile addr = new MockMultipartFile("ADDRESS_PROOF", "addr.pdf", "application/pdf",
                new byte[]{4, 5});
        when(service.submitDocuments(eq(provider), anyList(), eq(provider)))
                .thenReturn(verification());

        var response = providerController.submitDocuments(provider,
                Map.of("GOVERNMENT_ID", gov, "ADDRESS_PROOF", addr));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(service).submitDocuments(eq(provider), captor.capture(), eq(provider));
        assertThat((java.util.List<DocumentUpload>) captor.getValue()).hasSize(2);
    }

    @Test
    void submitDocuments_unknownType_isRejected() {
        authenticateProvider(provider);
        MultipartFile bogus = new MockMultipartFile("MYSTERY", "x.bin", "application/octet-stream",
                new byte[]{1});
        assertThatThrownBy(() -> providerController.submitDocuments(provider, Map.of("MYSTERY", bogus)))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void get_returnsVerificationRecord() {
        authenticateProvider(provider);
        when(service.getByProviderId(provider)).thenReturn(verification());
        var response = providerController.get(provider);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().providerId()).isEqualTo(provider);
    }

    /**
     * The dispatch gate carries no ownership assertion on purpose (the Dispatch Engine asks about
     * other providers), which is why this passes with no authenticated principal at all.
     */
    @Test
    void jobAssignmentEligibility_returns204WhenServiceAllows() {
        var response = providerController.assertJobAssignmentEligible(provider);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).assertCanReceiveJobAssignment(provider);
    }

    // ------------------------------------------------------------------ ownership (CallerIdentity)

    @Test
    void get_forAnotherProvidersRecord_isForbidden() {
        authenticateProvider(otherProvider);

        assertThatThrownBy(() -> providerController.get(provider))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> {
                    assertThat(((VerificationException) e).getErrorCode()).isEqualTo("FORBIDDEN");
                    assertThat(((VerificationException) e).getStatus().value()).isEqualTo(403);
                });

        verify(service, never()).getByProviderId(provider);
    }

    @Test
    void submitDocuments_forAnotherProvider_isForbidden() {
        authenticateProvider(otherProvider);
        MultipartFile gov = new MockMultipartFile("GOVERNMENT_ID", "id.jpg", "image/jpeg",
                new byte[]{1, 2, 3});

        assertThatThrownBy(() -> providerController.submitDocuments(provider,
                Map.of("GOVERNMENT_ID", gov)))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("FORBIDDEN"));

        verify(service, never()).submitDocuments(any(), anyList(), any());
    }

    @Test
    void get_withoutAuthentication_isUnauthorized() {
        assertThatThrownBy(() -> providerController.get(provider))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void get_withNonUuidPrincipal_isRejected() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "not-a-uuid", "n/a", AuthorityUtils.createAuthorityList("ROLE_SERVICE_PROVIDER")));

        assertThatThrownBy(() -> providerController.get(provider))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("INVALID_PRINCIPAL"));
    }

    @Test
    void get_ownRecord_succeeds() {
        authenticateProvider(provider);
        when(service.getByProviderId(provider)).thenReturn(verification());

        assertThat(providerController.get(provider).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void adminMayReadAnyProvidersRecord() {
        authenticateAdmin(admin.toString());
        when(service.getByProviderId(provider)).thenReturn(verification());

        var response = providerController.get(provider);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().providerId()).isEqualTo(provider);
    }

    @Test
    void supportAgentMayReadAnyProvidersRecord() {
        authenticateWithRole(UUID.randomUUID(), "SUPPORT_AGENT");
        when(service.getByProviderId(provider)).thenReturn(verification());

        assertThat(providerController.get(provider).getStatusCode().value()).isEqualTo(200);
    }
}
