package com.homefix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.domain.BackgroundCheckQueueRow;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.storage.DocumentStoragePort;
import com.homefix.verification.support.InMemoryVerificationRepository;

/**
 * The admin-recorded background-check step (Requirement 5.5-5.8): which providers wait on it, and
 * recording a result that approves or rejects them in one call. No background-check vendor is
 * integrated, so this is the only way a check completes.
 */
@ExtendWith(MockitoExtension.class)
class BackgroundCheckDecisionTest {

    private final UUID admin = UUID.randomUUID();

    @Mock
    private DocumentStoragePort documentStorage;
    @Mock
    private BackgroundCheckPort backgroundCheck;
    @Mock
    private ProviderNotificationPort notification;
    @Mock
    private DispatchPoolPort dispatchPool;

    private VerificationService service;

    @BeforeEach
    void setUp() {
        service = new VerificationService(new InMemoryVerificationRepository(), documentStorage, backgroundCheck,
                notification, dispatchPool, new VerificationProperties());
        lenient().when(documentStorage.store(any(), any(), any(), any()))
                .thenAnswer(inv -> "s3://bucket/" + inv.getArgument(1));
    }

    /** A provider whose documents were accepted, so the background check has started. */
    private UUID providerAtBackgroundCheck() {
        UUID providerId = UUID.randomUUID();
        service.submitDocuments(providerId, List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1}, "image/png"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[] {2}, "application/pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[] {3}, "application/pdf")),
                providerId);
        service.markDocumentsVerified(providerId, admin, "docs ok");
        return providerId;
    }

    @Test
    void theQueue_listsProvidersWhoseCheckStarted_andNobodyElse() {
        UUID waiting = providerAtBackgroundCheck();
        UUID submittedOnly = UUID.randomUUID();
        service.submitDocuments(submittedOnly, List.of(
                new DocumentUpload(DocumentType.GOVERNMENT_ID, new byte[] {1}, "image/png"),
                new DocumentUpload(DocumentType.ADDRESS_PROOF, new byte[] {2}, "application/pdf"),
                new DocumentUpload(DocumentType.SKILL_CERTIFICATION, new byte[] {3}, "application/pdf")),
                submittedOnly);

        List<BackgroundCheckQueueRow> queue = service.backgroundCheckQueue(200);

        assertThat(queue).extracting(BackgroundCheckQueueRow::providerId).containsExactly(waiting);
        assertThat(queue.get(0).status()).isEqualTo(VerificationStatus.BACKGROUND_CHECK_PENDING);
        assertThat(queue.get(0).startedAt()).isNotNull();
        assertThat(queue.get(0).documentCount()).isEqualTo(3L);
    }

    @Test
    void aPassedCheck_recordsTheResult_andApprovesTheProviderForJobs() {
        UUID providerId = providerAtBackgroundCheck();

        Verification v = service.decideBackgroundCheck(providerId, admin, true, "  No records found  ", null);

        assertThat(v.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(v.getBackgroundCheckResult()).isEqualTo("No records found");
        assertThat(service.backgroundCheckQueue(200)).isEmpty();
        verify(notification, never()).notifyRejected(any(), any());
    }

    @Test
    void aFailedCheck_rejectsTheProvider_andTellsThemWhy() {
        UUID providerId = providerAtBackgroundCheck();

        Verification v = service.decideBackgroundCheck(providerId, admin, false, "Pending case found",
                "Your background check did not clear");

        assertThat(v.getStatus()).isEqualTo(VerificationStatus.REJECTED);
        verify(notification).notifyRejected(providerId, "Your background check did not clear");
    }

    @Test
    void aFailedCheck_needsAReason_andEveryDecisionNeedsAResult() {
        UUID providerId = providerAtBackgroundCheck();

        assertThatThrownBy(() -> service.decideBackgroundCheck(providerId, admin, false, "Pending case", " "))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.decideBackgroundCheck(providerId, admin, true, "", null))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aResultRecordedEarlier_canStillBeDecided() {
        UUID providerId = providerAtBackgroundCheck();
        service.completeBackgroundCheck(providerId, admin, "clear");
        assertThat(service.backgroundCheckQueue(200)).extracting(BackgroundCheckQueueRow::status)
                .containsExactly(VerificationStatus.BACKGROUND_CHECK_COMPLETED);

        assertThat(service.decideBackgroundCheck(providerId, admin, true, "clear", null).getStatus())
                .isEqualTo(VerificationStatus.APPROVED);
    }

    @Test
    void aProviderNotAtThisStep_cannotBeDecidedHere_evenASuspendedOne() {
        UUID providerId = providerAtBackgroundCheck();
        service.decideBackgroundCheck(providerId, admin, true, "clear", null);
        service.suspend(providerId, admin, "complaints");

        // SUSPENDED -> APPROVED is a legal transition, but reinstating is not this step's job.
        assertThatThrownBy(() -> service.decideBackgroundCheck(providerId, admin, true, "clear", null))
                .isInstanceOfSatisfying(VerificationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
