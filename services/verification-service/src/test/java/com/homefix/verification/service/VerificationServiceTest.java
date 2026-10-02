package com.homefix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationAuditEntry;
import com.homefix.verification.domain.VerificationDocument;
import com.homefix.verification.domain.VerificationQueueRow;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.storage.DocumentStoragePort;
import com.homefix.verification.support.InMemoryVerificationRepository;

/**
 * Unit tests for {@link VerificationService} (Requirement 5).
 *
 * <p>Operates against an in-memory fake repository and mocked ports — no Spring context,
 * database, or network. Covers document upload (5.3), each Admin workflow step (5.4–5.9),
 * invalid-transition rejection with descriptive error content (5.2, Property 24), the job
 * assignment gate (5.10), and audit-trail chain integrity (5.11).
 */
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    private InMemoryVerificationRepository repository;

    @Mock
    private DocumentStoragePort documentStorage;
    @Mock
    private BackgroundCheckPort backgroundCheck;
    @Mock
    private ProviderNotificationPort notification;
    @Mock
    private DispatchPoolPort dispatchPool;

    private VerificationProperties properties;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryVerificationRepository();
        properties = new VerificationProperties();
        service = new VerificationService(
                repository, documentStorage, backgroundCheck, notification, dispatchPool, properties);
    }

    private static List<DocumentUpload> allRequiredDocuments() {
        return List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1, 2, 3}, "image/png"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[] {4, 5}, "application/pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[] {6}, "application/pdf"));
    }

    /** Stubs the storage port to return a synthetic ref for any upload. */
    private void stubStorage() {
        lenient().when(documentStorage.store(any(), any(), any(), any()))
                .thenAnswer(inv -> "s3://bucket/" + inv.getArgument(1));
    }

    /** Drives a provider all the way to APPROVED and returns its id. */
    private UUID seedApprovedProvider() {
        stubStorage();
        UUID providerId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        service.submitDocuments(providerId, allRequiredDocuments(), providerId);
        service.markDocumentsVerified(providerId, admin, "docs ok");
        service.completeBackgroundCheck(providerId, admin, "clear");
        service.approve(providerId, admin, "approved");
        return providerId;
    }

    // ============================= Document Upload (5.3) =========================

    @Nested
    class DocumentUploadFlow {

        @Test
        void submittingAllRequiredDocuments_storesAndTransitionsToSubmitted() {
            stubStorage();
            UUID providerId = UUID.randomUUID();

            Verification v = service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.DOCUMENT_SUBMITTED);
            assertThat(v.getDocuments()).hasSize(3);
            // Each document was pushed to encrypted object storage.
            verify(documentStorage).store(eq(providerId), eq(DocumentType.GOVERNMENT_ID), any(), any());
            verify(documentStorage).store(eq(providerId), eq(DocumentType.ADDRESS_PROOF), any(), any());
            verify(documentStorage).store(eq(providerId), eq(DocumentType.SKILL_CERTIFICATION), any(), any());
        }

        @Test
        void submittingWithMissingDocumentType_isRejected() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            List<DocumentUpload> partial = List.of(
                    new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1}, "image/png"));

            assertThatThrownBy(() -> service.submitDocuments(providerId, partial, providerId))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> {
                        VerificationException ve = (VerificationException) ex;
                        assertThat(ve.getErrorCode()).isEqualTo("MISSING_REQUIRED_DOCUMENTS");
                        assertThat(ve.getMessage())
                                .contains("ADDRESS_PROOF").contains("SKILL_CERTIFICATION");
                    });
        }

        @Test
        void submittingNoDocuments_isRejected() {
            UUID providerId = UUID.randomUUID();
            assertThatThrownBy(() -> service.submitDocuments(providerId, List.of(), providerId))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> assertThat(((VerificationException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    // ============================= Valid Workflow Transitions (5.4–5.9) ==========

    @Nested
    class ValidTransitions {

        @Test
        void markDocumentsVerified_transitionsAndTriggersBackgroundCheck() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            Verification v = service.markDocumentsVerified(providerId, admin, "docs verified");

            // Auto-advances through DOCUMENT_VERIFIED into BACKGROUND_CHECK_PENDING (5.4, 5.5).
            assertThat(v.getStatus()).isEqualTo(VerificationStatus.BACKGROUND_CHECK_PENDING);
            assertThat(v.getBackgroundCheckStartedAt()).isNotNull();
            verify(backgroundCheck).initiate(providerId);
        }

        @Test
        void completeBackgroundCheck_storesResultAndTransitions() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            service.markDocumentsVerified(providerId, admin, null);

            Verification v = service.completeBackgroundCheck(providerId, admin, "CLEAR");

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED);
            assertThat(v.getBackgroundCheckResult()).isEqualTo("CLEAR");
        }

        @Test
        void approve_transitionsToApproved() {
            UUID providerId = seedApprovedProvider();
            assertThat(repository.findByProviderId(providerId).orElseThrow().getStatus())
                    .isEqualTo(VerificationStatus.APPROVED);
        }

        @Test
        void reject_recordsReasonAndNotifiesProvider() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            Verification v = service.reject(providerId, admin, "forged government id");

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.REJECTED);
            verify(notification).notifyRejected(providerId, "forged government id");
        }

        @Test
        void suspend_removesFromDispatchPool() {
            UUID providerId = seedApprovedProvider();
            UUID admin = UUID.randomUUID();

            Verification v = service.suspend(providerId, admin, "customer complaints");

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.SUSPENDED);
            verify(dispatchPool).deactivateProvider(providerId);
        }

        @Test
        void suspendedProvider_canBeReinstatedToApproved() {
            UUID providerId = seedApprovedProvider();
            UUID admin = UUID.randomUUID();
            service.suspend(providerId, admin, "temp");

            Verification v = service.approve(providerId, admin, "reinstated");

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        }

        @Test
        void rejectWithBlankReason_isRejected() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            assertThatThrownBy(() -> service.reject(providerId, UUID.randomUUID(), "  "))
                    .isInstanceOf(VerificationException.class);
            // No notification is sent when the request is invalid.
            verifyNoInteractions(notification);
        }
    }

    // ============================= Invalid Transitions (5.2, Property 24) ========

    @Nested
    class InvalidTransitions {

        @Test
        void approveFromPending_isRejectedWithDescriptiveError() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            // Create a PENDING record without submitting documents.
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            // Now in DOCUMENT_SUBMITTED; approving directly is not permitted.

            assertThatThrownBy(() -> service.approve(providerId, UUID.randomUUID(), "too soon"))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> {
                        VerificationException ve = (VerificationException) ex;
                        assertThat(ve.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ve.getErrorCode()).isEqualTo("INVALID_STATE_TRANSITION");
                        // Error names the current state and the disallowed target (5.2).
                        assertThat(ve.getMessage())
                                .contains("DOCUMENT_SUBMITTED").contains("APPROVED");
                        assertThat(ve.getDetails())
                                .anyMatch(d -> d.contains("currentState=DOCUMENT_SUBMITTED"))
                                .anyMatch(d -> d.contains("disallowedTarget=APPROVED"));
                    });
        }

        @Test
        void suspendFromNonApproved_isRejected_andDispatchPoolUntouched() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            assertThatThrownBy(() -> service.suspend(providerId, UUID.randomUUID(), "bad"))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> assertThat(((VerificationException) ex).getErrorCode())
                            .isEqualTo("INVALID_STATE_TRANSITION"));
            // The dispatch-pool side effect must not fire on a rejected transition.
            verify(dispatchPool, never()).deactivateProvider(any());
        }

        @Test
        void completeBackgroundCheckBeforePending_isRejected() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            // Still DOCUMENT_SUBMITTED; cannot jump to BACKGROUND_CHECK_COMPLETED.

            assertThatThrownBy(() -> service.completeBackgroundCheck(providerId, UUID.randomUUID(), "x"))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> assertThat(((VerificationException) ex).getErrorCode())
                            .isEqualTo("INVALID_STATE_TRANSITION"));
        }

        @Test
        void actionOnUnknownProvider_isNotFound() {
            assertThatThrownBy(() -> service.approve(UUID.randomUUID(), UUID.randomUUID(), "x"))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> assertThat(((VerificationException) ex).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ============================= Job Assignment Gate (5.10) ====================

    @Nested
    class JobAssignmentGate {

        @Test
        void approvedProvider_canReceiveJobAssignment() {
            UUID providerId = seedApprovedProvider();
            assertThat(service.canReceiveJobAssignment(providerId)).isTrue();
            // assertCanReceiveJobAssignment does not throw for an APPROVED provider.
            service.assertCanReceiveJobAssignment(providerId);
        }

        @Test
        void nonApprovedProvider_isBlockedWith403() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            assertThat(service.canReceiveJobAssignment(providerId)).isFalse();
            assertThatThrownBy(() -> service.assertCanReceiveJobAssignment(providerId))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> {
                        VerificationException ve = (VerificationException) ex;
                        assertThat(ve.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ve.getErrorCode()).isEqualTo("PROVIDER_NOT_APPROVED");
                    });
        }

        @Test
        void suspendedProvider_isBlocked() {
            UUID providerId = seedApprovedProvider();
            service.suspend(providerId, UUID.randomUUID(), "temp");
            assertThat(service.canReceiveJobAssignment(providerId)).isFalse();
        }

        @Test
        void unknownProvider_isBlocked() {
            assertThat(service.canReceiveJobAssignment(UUID.randomUUID())).isFalse();
        }
    }

    // ============================= Audit Trail (5.11) ============================

    @Nested
    class AuditTrail {

        @Test
        void everyTransitionRecordsActorReasonAndStates() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();

            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            service.markDocumentsVerified(providerId, admin, "docs ok");
            service.completeBackgroundCheck(providerId, admin, "clear");
            service.approve(providerId, admin, "approved");

            Verification v = repository.findByProviderId(providerId).orElseThrow();
            List<VerificationAuditEntry> trail = v.getAuditTrail();

            // PENDING->DOCUMENT_SUBMITTED, ->DOCUMENT_VERIFIED, ->BACKGROUND_CHECK_PENDING,
            // ->BACKGROUND_CHECK_COMPLETED, ->APPROVED = 5 entries.
            assertThat(trail).hasSize(5);

            // First entry starts from the seed PENDING state and records the submitter.
            VerificationAuditEntry first = trail.get(0);
            assertThat(first.getFromState()).isEqualTo(VerificationStatus.PENDING);
            assertThat(first.getToState()).isEqualTo(VerificationStatus.DOCUMENT_SUBMITTED);
            assertThat(first.getActorId()).isEqualTo(providerId);

            // Every entry carries a non-null actor, reason, and timestamp.
            for (VerificationAuditEntry e : trail) {
                assertThat(e.getActorId()).isNotNull();
                assertThat(e.getReason()).isNotBlank();
                assertThat(e.getCreatedAt()).isNotNull();
            }
        }

        @Test
        void auditTrailFormsContiguousChain() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();

            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            service.markDocumentsVerified(providerId, admin, "docs ok");
            service.completeBackgroundCheck(providerId, admin, "clear");
            service.approve(providerId, admin, "approved");
            service.suspend(providerId, admin, "complaint");

            List<VerificationAuditEntry> trail =
                    repository.findByProviderId(providerId).orElseThrow().getAuditTrail();

            // Sequence numbers are contiguous starting at 0, and each entry's fromState
            // equals the previous entry's toState (a contiguous chain, Requirement 5.11).
            for (int i = 0; i < trail.size(); i++) {
                assertThat(trail.get(i).getSequence()).isEqualTo(i);
                if (i > 0) {
                    assertThat(trail.get(i).getFromState())
                            .as("entry %d fromState chains from entry %d toState", i, i - 1)
                            .isEqualTo(trail.get(i - 1).getToState());
                }
            }
            // Chain ends in the current state.
            assertThat(trail.get(trail.size() - 1).getToState())
                    .isEqualTo(VerificationStatus.SUSPENDED);
        }

        @Test
        void rejectedTransitionAddsNoAuditEntry() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            int before = repository.findByProviderId(providerId).orElseThrow().getAuditTrail().size();

            assertThatThrownBy(() -> service.approve(providerId, UUID.randomUUID(), "too soon"))
                    .isInstanceOf(VerificationException.class);

            int after = repository.findByProviderId(providerId).orElseThrow().getAuditTrail().size();
            assertThat(after).isEqualTo(before);
        }
    }

    // ============================= Admin Portal views (Req 19.2, 19.3) ===========

    @Nested
    class AdminPortalViews {

        @Test
        void reinstate_returnsASuspendedProviderToApprovedWithAnAuditEntry() {
            UUID providerId = seedApprovedProvider();
            UUID admin = UUID.randomUUID();
            service.suspend(providerId, admin, "complaint upheld");

            Verification v = service.reinstate(providerId, admin, null);

            assertThat(v.getStatus()).isEqualTo(VerificationStatus.APPROVED);
            VerificationAuditEntry last = v.getAuditTrail().get(v.getAuditTrail().size() - 1);
            assertThat(last.getFromState()).isEqualTo(VerificationStatus.SUSPENDED);
            assertThat(last.getActorId()).isEqualTo(admin);
            assertThat(last.getReason()).isEqualTo("Admin reinstated provider");
        }

        @Test
        void reinstate_neverPerformsAFirstTimeApproval() {
            // BACKGROUND_CHECK_COMPLETED -> APPROVED is a legal transition, but it is an approval,
            // not a reinstatement: reinstate must refuse it.
            stubStorage();
            UUID providerId = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            service.markDocumentsVerified(providerId, admin, null);
            service.completeBackgroundCheck(providerId, admin, "clear");

            assertThatThrownBy(() -> service.reinstate(providerId, admin, null))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(e -> {
                        VerificationException ve = (VerificationException) e;
                        assertThat(ve.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ve.getErrorCode()).isEqualTo("INVALID_STATE_TRANSITION");
                        assertThat(ve.getDetails()).contains("currentState=BACKGROUND_CHECK_COMPLETED");
                    });
            assertThat(repository.findByProviderId(providerId).orElseThrow().getStatus())
                    .isEqualTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED);
        }

        @Test
        void reviewQueue_listsOnlySubmittedProvidersWithTheirDocumentCount() {
            stubStorage();
            UUID waiting = UUID.randomUUID();
            service.submitDocuments(waiting, allRequiredDocuments(), waiting);
            UUID approved = seedApprovedProvider();

            List<VerificationQueueRow> queue = service.reviewQueue(200);

            assertThat(queue).extracting(VerificationQueueRow::providerId).containsExactly(waiting);
            assertThat(queue.get(0).documentCount()).isEqualTo(3L);
            assertThat(queue.get(0).submittedAt()).isNotNull();
            assertThat(queue).extracting(VerificationQueueRow::providerId).doesNotContain(approved);
        }

        @Test
        void reviewQueue_honoursTheLimit() {
            stubStorage();
            for (int i = 0; i < 3; i++) {
                UUID providerId = UUID.randomUUID();
                service.submitDocuments(providerId, allRequiredDocuments(), providerId);
            }

            assertThat(service.reviewQueue(2)).hasSize(2);
        }

        @Test
        void documentsOf_returnsTheDocumentsOnFile() {
            stubStorage();
            UUID providerId = UUID.randomUUID();
            service.submitDocuments(providerId, allRequiredDocuments(), providerId);

            List<VerificationDocument> documents = service.documentsOf(providerId);

            assertThat(documents).extracting(VerificationDocument::getDocumentType)
                    .containsExactlyInAnyOrder(DocumentType.GOVERNMENT_ID, DocumentType.ADDRESS_PROOF,
                            DocumentType.SKILL_CERTIFICATION);
        }

        @Test
        void documentsOf_unknownProvider_isNotFound() {
            assertThatThrownBy(() -> service.documentsOf(UUID.randomUUID()))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(e -> assertThat(((VerificationException) e).getErrorCode())
                            .isEqualTo("VERIFICATION_NOT_FOUND"));
        }

        @Test
        void statusesAmong_reportsKnownProvidersAndOmitsUnknownOnes() {
            stubStorage();
            UUID submitted = UUID.randomUUID();
            service.submitDocuments(submitted, allRequiredDocuments(), submitted);
            UUID approved = seedApprovedProvider();
            UUID unknown = UUID.randomUUID();

            Map<UUID, VerificationStatus> statuses = service.statusesAmong(List.of(submitted, approved, unknown));

            assertThat(statuses).containsOnly(
                    Map.entry(submitted, VerificationStatus.DOCUMENT_SUBMITTED),
                    Map.entry(approved, VerificationStatus.APPROVED));
            assertThat(service.statusesAmong(List.of())).isEmpty();
        }
    }
}
