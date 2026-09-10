package com.homefix.verification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
 */
class VerificationControllersTest {

    private VerificationService service;
    private AdminVerificationController adminController;
    private VerificationController providerController;
    private final UUID admin = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(VerificationService.class);
        adminController = new AdminVerificationController(service);
        providerController = new VerificationController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAdmin(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
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
        MultipartFile bogus = new MockMultipartFile("MYSTERY", "x.bin", "application/octet-stream",
                new byte[]{1});
        assertThatThrownBy(() -> providerController.submitDocuments(provider, Map.of("MYSTERY", bogus)))
                .isInstanceOf(VerificationException.class)
                .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                        .isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void get_returnsVerificationRecord() {
        when(service.getByProviderId(provider)).thenReturn(verification());
        var response = providerController.get(provider);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().providerId()).isEqualTo(provider);
    }

    @Test
    void jobAssignmentEligibility_returns204WhenServiceAllows() {
        var response = providerController.assertJobAssignmentEligible(provider);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).assertCanReceiveJobAssignment(provider);
    }
}
