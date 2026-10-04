package com.homefix.verification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
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

import com.homefix.shared.security.HomefixSecurityAutoConfiguration;
import com.homefix.verification.config.VerificationRbacConfig;
import com.homefix.verification.config.WebSecurityConfig;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationQueueRow;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.domain.BackgroundCheckQueueRow;
import com.homefix.verification.provider.ProviderDirectoryPort;
import com.homefix.verification.provider.ProviderDirectoryPort.ProviderSummary;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.service.VerificationService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the Admin Portal's Verification Queue endpoints through the service's real
 * security chain ({@link WebSecurityConfig}, the shared JWT and RBAC filters and the rules from
 * {@link VerificationRbacConfig}): each response is exactly the portal's JSON shape
 * ({@code VerificationQueueEntry}, {@code VerificationDocument}), the decision drives the existing
 * workflow transitions with the acting Admin, and a non-admin token is refused before the service
 * is touched.
 */
@WebMvcTest(controllers = AdminVerificationQueueController.class, properties = {
        "homefix.security.jwt-secret=" + AdminVerificationQueueControllerTest.JWT_SECRET,
        "homefix.verification.internal-api-key=unused-internal-key"
})
@Import({WebSecurityConfig.class, VerificationRbacConfig.class, CallerIdentity.class,
        GlobalExceptionHandler.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class AdminVerificationQueueControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID admin = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerificationService verificationService;

    @MockBean
    private ProviderDirectoryPort providerDirectory;

    private String token(UUID subject, String... roles) {
        return "Bearer " + Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // ------------------------------------------------------------------ queue

    @Test
    void queue_returnsThePortalShapeWithBorrowedNamesAndTheServerCap() throws Exception {
        Instant uploaded = Instant.parse("2026-09-30T10:15:30Z");
        UUID unnamed = UUID.randomUUID();
        when(verificationService.reviewQueue(anyInt())).thenReturn(List.of(
                new VerificationQueueRow(provider, 3L, uploaded, Instant.parse("2026-09-30T10:15:31Z")),
                new VerificationQueueRow(unnamed, 0L, null, Instant.parse("2026-10-01T08:00:00Z"))));
        when(providerDirectory.summariesOf(any())).thenReturn(
                Map.of(provider, new ProviderSummary("Ravi Kumar", "plumbing")));

        mockMvc.perform(get("/admin/verification/queue").header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].providerId").value(provider.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].primarySkill").value("plumbing"))
                .andExpect(jsonPath("$[0].mobileNumber").isEmpty())
                .andExpect(jsonPath("$[0].submittedAt").value("2026-09-30T10:15:30Z"))
                .andExpect(jsonPath("$[0].documentCount").value(3))
                // No profile / directory miss: names are null, submittedAt falls back to updatedAt.
                .andExpect(jsonPath("$[1].displayName").isEmpty())
                .andExpect(jsonPath("$[1].submittedAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$[1].documentCount").value(0));

        verify(verificationService).reviewQueue(AdminVerificationQueueController.QUEUE_LIMIT);
        verify(providerDirectory).summariesOf(List.of(provider, unnamed));
    }

    @Test
    void backgroundChecks_listsTheProvidersAtThatStep() throws Exception {
        Instant started = Instant.parse("2026-10-04T16:37:52Z");
        when(verificationService.backgroundCheckQueue(anyInt())).thenReturn(List.of(
                new BackgroundCheckQueueRow(provider, VerificationStatus.BACKGROUND_CHECK_PENDING, started, null, 3L)));
        when(providerDirectory.summariesOf(any())).thenReturn(
                Map.of(provider, new ProviderSummary("Ravi Kumar", "plumbing")));

        mockMvc.perform(get("/admin/verification/background-checks")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerId").value(provider.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Ravi Kumar"))
                .andExpect(jsonPath("$[0].status").value("BACKGROUND_CHECK_PENDING"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-04T16:37:52Z"))
                .andExpect(jsonPath("$[0].documentCount").value(3));
    }

    @Test
    void backgroundCheckDecision_recordsTheResultAsTheCallingAdmin() throws Exception {
        mockMvc.perform(post("/admin/verification/{id}/background-check", provider)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"FAILED\",\"result\":\"Pending case\",\"reason\":\"Not cleared\"}"))
                .andExpect(status().isNoContent());

        verify(verificationService).decideBackgroundCheck(provider, admin, false, "Pending case", "Not cleared");
    }

    @Test
    void backgroundCheckDecision_withoutAResult_is400_andIsAdminOnly() throws Exception {
        mockMvc.perform(post("/admin/verification/{id}/background-check", provider)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"PASSED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/verification/{id}/background-check", provider)
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "SUPPORT_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"PASSED\",\"result\":\"clear\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(verificationService);
    }

    @Test
    void emptyQueue_skipsTheDirectoryLookup() throws Exception {
        when(verificationService.reviewQueue(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/verification/queue").header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verifyNoInteractions(providerDirectory);
    }

    @Test
    void queue_isRefusedForNonAdminRoles() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER")) {
            mockMvc.perform(get("/admin/verification/queue").header(HttpHeaders.AUTHORIZATION, token(admin, role)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/verification/queue")).andExpect(status().isUnauthorized());

        verifyNoInteractions(verificationService);
    }

    // ------------------------------------------------------------------ documents

    @Test
    void documents_returnsThePortalShape() throws Exception {
        Verification v = Verification.create(provider);
        v.addDocument(DocumentType.GOVERNMENT_ID, "s3://bucket/key", "image/jpeg", 12);
        when(verificationService.documentsOf(provider)).thenReturn(v.getDocuments());

        mockMvc.perform(get("/admin/verification/" + provider + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(v.getDocuments().get(0).getId().toString()))
                .andExpect(jsonPath("$[0].type").value("Government ID"))
                .andExpect(jsonPath("$[0].contentType").value("image/jpeg"))
                // No presigned URL can be produced yet, and the raw storage ref is never exposed.
                .andExpect(jsonPath("$[0].url").isEmpty())
                .andExpect(jsonPath("$[0].fileName").isEmpty())
                .andExpect(jsonPath("$[0].storageRef").doesNotExist());
    }

    @Test
    void documents_unknownProvider_is404() throws Exception {
        when(verificationService.documentsOf(provider))
                .thenThrow(VerificationException.notFound("No verification record"));

        mockMvc.perform(get("/admin/verification/" + provider + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VERIFICATION_NOT_FOUND"));
    }

    @Test
    void documents_isRefusedForAProviderEvenTheirOwn() throws Exception {
        mockMvc.perform(get("/admin/verification/" + provider + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }

    // ------------------------------------------------------------------ decision

    @Test
    void approve_drivesVerifyDocumentsWithTheActingAdminAndAnswers204() throws Exception {
        mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());

        verify(verificationService).markDocumentsVerified(provider, admin, null);
        verify(verificationService, never()).reject(any(), any(), any());
    }

    @Test
    void reject_drivesRejectWithTheReason() throws Exception {
        mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"ID photo unreadable\"}"))
                .andExpect(status().isNoContent());

        verify(verificationService).reject(provider, admin, "ID photo unreadable");
        verify(verificationService, never()).markDocumentsVerified(any(), any(), any());
    }

    @Test
    void reject_withoutReason_isTheServicesValidationError() throws Exception {
        when(verificationService.reject(eq(provider), eq(admin), eq(null)))
                .thenThrow(VerificationException.validation("A rejection reason is required"));

        mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void decision_inTheWrongState_is409() throws Exception {
        when(verificationService.markDocumentsVerified(provider, admin, null))
                .thenThrow(new VerificationException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "Transition from APPROVED to DOCUMENT_VERIFIED is not permitted"));

        mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void missingOrUnknownDecision_is400InTheSharedEnvelope() throws Exception {
        for (String body : List.of("{}", "{\"decision\":\"MAYBE\"}")) {
            mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                            .header(HttpHeaders.AUTHORIZATION, token(admin, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(verificationService);
    }

    @Test
    void decision_isRefusedForNonAdmins() throws Exception {
        mockMvc.perform(post("/admin/verification/" + provider + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, token(provider, "SERVICE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }
}
