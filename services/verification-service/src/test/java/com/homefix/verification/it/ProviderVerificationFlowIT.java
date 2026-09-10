package com.homefix.verification.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationAuditEntry;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.service.DocumentUpload;
import com.homefix.verification.service.VerificationService;
import com.homefix.verification.storage.DocumentStoragePort;
import com.homefix.verification.support.InMemoryVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the Provider verification flow (Task 45; Requirement 5).
 *
 * <p>Drives the <em>real</em> {@link VerificationService} — including the real verification state
 * machine and audit trail — through the full lifecycle a provider travels from application to
 * dispatch eligibility:
 *
 * <pre>
 *   document upload           → DOCUMENT_SUBMITTED       (5.3)
 *   Admin marks verified      → DOCUMENT_VERIFIED,
 *                               background check initiated → BACKGROUND_CHECK_PENDING (5.4, 5.5)
 *   background check received  → BACKGROUND_CHECK_COMPLETED (5.6)
 *   Admin approves            → APPROVED                  (5.7)
 *   ⇒ provider now appears in the dispatch candidate pool (5.7, 5.10)
 * </pre>
 *
 * <p>The cross-service seams are modelled the way the architecture defines them: the object
 * storage, background-check, and provider-notification collaborators are fakes, and the Dispatch
 * Engine's candidate pool is represented by {@link DispatchCandidatePool}, which sources its
 * membership from the verification service's own APPROVED gate ({@code canReceiveJobAssignment}) —
 * exactly the check the Dispatch Engine performs before offering a job (Requirement 5.10). The
 * flow therefore genuinely produces an APPROVED provider that is visible to dispatch, and a
 * subsequent suspension removes the provider from that pool (Requirement 5.9).
 */
class ProviderVerificationFlowIT {

    private InMemoryVerificationRepository repository;
    private RecordingBackgroundCheck backgroundCheck;
    private RecordingDispatchPool dispatchPool;
    private VerificationService service;
    private DispatchCandidatePool candidatePool;

    private final UUID providerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new InMemoryVerificationRepository();
        backgroundCheck = new RecordingBackgroundCheck();
        dispatchPool = new RecordingDispatchPool();
        DocumentStoragePort storage = (provider, type, content, contentType) ->
                "s3://homefix-documents/" + provider + "/" + type;
        ProviderNotificationPort notification = (provider, reason) -> { };
        service = new VerificationService(repository, storage, backgroundCheck, notification,
                dispatchPool, new VerificationProperties());
        // The Dispatch Engine only ever offers jobs to APPROVED providers (Requirement 5.10), so
        // the candidate pool is exactly the set of providers passing the verification approval gate.
        candidatePool = new DispatchCandidatePool(service, dispatchPool);
    }

    @Test
    @DisplayName("Full verification flow yields an APPROVED provider visible to the dispatch candidate pool")
    void documentUploadThroughApproval_makesProviderVisibleToDispatch() {
        // Before anything, the provider is not a dispatch candidate.
        assertThat(candidatePool.contains(providerId)).isFalse();

        // 1) Document upload → DOCUMENT_SUBMITTED (Requirement 5.3).
        Verification afterUpload = service.submitDocuments(providerId, allRequiredDocuments(), providerId);
        assertThat(afterUpload.getStatus()).isEqualTo(VerificationStatus.DOCUMENT_SUBMITTED);
        assertThat(afterUpload.getDocuments()).hasSize(3);
        assertThat(candidatePool.contains(providerId)).isFalse();

        // 2) Admin marks documents verified → DOCUMENT_VERIFIED and a background check is
        //    initiated, advancing to BACKGROUND_CHECK_PENDING (Requirements 5.4, 5.5).
        Verification afterVerify = service.markDocumentsVerified(providerId, adminId, "documents authentic");
        assertThat(afterVerify.getStatus()).isEqualTo(VerificationStatus.BACKGROUND_CHECK_PENDING);
        assertThat(afterVerify.getBackgroundCheckStartedAt()).isNotNull();
        assertThat(backgroundCheck.initiatedFor).containsExactly(providerId);
        assertThat(candidatePool.contains(providerId)).isFalse();

        // 3) Background check result received → BACKGROUND_CHECK_COMPLETED (Requirement 5.6).
        Verification afterCheck = service.completeBackgroundCheck(providerId, adminId, "CLEAR");
        assertThat(afterCheck.getStatus()).isEqualTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED);
        assertThat(afterCheck.getBackgroundCheckResult()).isEqualTo("CLEAR");
        assertThat(candidatePool.contains(providerId)).isFalse();

        // 4) Admin approves → APPROVED (Requirement 5.7).
        Verification approved = service.approve(providerId, adminId, "all checks passed");
        assertThat(approved.getStatus()).isEqualTo(VerificationStatus.APPROVED);

        // ⇒ The provider is now visible to the Dispatch Engine candidate pool (Requirements 5.7, 5.10).
        assertThat(service.canReceiveJobAssignment(providerId)).isTrue();
        assertThat(candidatePool.contains(providerId)).isTrue();

        // The audit trail is a contiguous chain across the whole flow (Requirement 5.11):
        // PENDING→DOCUMENT_SUBMITTED→DOCUMENT_VERIFIED→BACKGROUND_CHECK_PENDING→
        // BACKGROUND_CHECK_COMPLETED→APPROVED = 5 recorded transitions.
        List<VerificationAuditEntry> trail = repository.findByProviderId(providerId).orElseThrow().getAuditTrail();
        assertThat(trail).hasSize(5);
        assertThat(trail.get(trail.size() - 1).getToState()).isEqualTo(VerificationStatus.APPROVED);
        for (int i = 0; i < trail.size(); i++) {
            assertThat(trail.get(i).getSequence()).isEqualTo(i);
            assertThat(trail.get(i).getActorId()).isNotNull();
            assertThat(trail.get(i).getReason()).isNotBlank();
            if (i > 0) {
                assertThat(trail.get(i).getFromState()).isEqualTo(trail.get(i - 1).getToState());
            }
        }
    }

    @Test
    @DisplayName("Suspending an APPROVED provider removes them from the dispatch candidate pool")
    void suspendingApprovedProvider_removesFromDispatchPool() {
        driveToApproved();
        assertThat(candidatePool.contains(providerId)).isTrue();

        // Admin suspends the provider (Requirement 5.9): removed from the active dispatch pool.
        service.suspend(providerId, adminId, "quality complaints under review");

        assertThat(service.canReceiveJobAssignment(providerId)).isFalse();
        assertThat(candidatePool.contains(providerId)).isFalse();
        assertThat(dispatchPool.deactivated).contains(providerId);
    }

    // ---------------------------------------------------------------------
    // Helpers and fakes
    // ---------------------------------------------------------------------

    private void driveToApproved() {
        service.submitDocuments(providerId, allRequiredDocuments(), providerId);
        service.markDocumentsVerified(providerId, adminId, "documents authentic");
        service.completeBackgroundCheck(providerId, adminId, "CLEAR");
        service.approve(providerId, adminId, "all checks passed");
    }

    private static List<DocumentUpload> allRequiredDocuments() {
        return List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1, 2, 3}, "image/png"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[] {4, 5}, "application/pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[] {6}, "application/pdf"));
    }

    /** Records which providers a background check was initiated for (Requirement 5.5). */
    static final class RecordingBackgroundCheck implements BackgroundCheckPort {
        final List<UUID> initiatedFor = new ArrayList<>();

        @Override
        public String initiate(UUID providerId) {
            initiatedFor.add(providerId);
            return "bgcheck-ref-" + providerId;
        }
    }

    /** Records providers removed from the active dispatch pool on suspension (Requirement 5.9). */
    static final class RecordingDispatchPool implements DispatchPoolPort {
        final Set<UUID> deactivated = ConcurrentHashMap.newKeySet();

        @Override
        public void deactivateProvider(UUID providerId) {
            deactivated.add(providerId);
        }
    }

    /**
     * Stand-in for the Dispatch Engine's active candidate pool. A provider is a candidate iff the
     * verification service reports them APPROVED (the exact gate the Dispatch Engine applies,
     * Requirement 5.10) and they have not been deactivated by a suspension (Requirement 5.9).
     */
    static final class DispatchCandidatePool {
        private final VerificationService verificationService;
        private final RecordingDispatchPool dispatchPool;

        DispatchCandidatePool(VerificationService verificationService, RecordingDispatchPool dispatchPool) {
            this.verificationService = verificationService;
            this.dispatchPool = dispatchPool;
        }

        boolean contains(UUID providerId) {
            return verificationService.canReceiveJobAssignment(providerId)
                    && !dispatchPool.deactivated.contains(providerId);
        }
    }
}
