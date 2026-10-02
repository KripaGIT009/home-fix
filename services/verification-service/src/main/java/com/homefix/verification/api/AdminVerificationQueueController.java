package com.homefix.verification.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.verification.api.dto.VerificationDecisionRequest;
import com.homefix.verification.api.dto.VerificationDocumentResponse;
import com.homefix.verification.api.dto.VerificationQueueEntryResponse;
import com.homefix.verification.domain.VerificationQueueRow;
import com.homefix.verification.provider.ProviderDirectoryPort;
import com.homefix.verification.provider.ProviderDirectoryPort.ProviderSummary;
import com.homefix.verification.service.VerificationService;

import jakarta.validation.Valid;

/**
 * The Admin Portal's Verification Queue (Requirement 19.3), at the paths the portal calls
 * ({@code /admin/verification/...}, singular). The API Gateway routes them here unchanged.
 *
 * <p>This is a portal-shaped view over the existing workflow, not a second workflow: the decision
 * endpoint drives the same {@link VerificationService} transitions as
 * {@link AdminVerificationController} ({@code verify-documents} and {@code reject}), so the state
 * machine, the audit entry with the acting Admin (Requirement 5.11) and the rejection notification
 * all apply unchanged. Role enforcement (ADMIN / SUPER_ADMIN) is applied by the shared
 * {@code RbacEnforcementFilter} using the rules in {@code VerificationRbacConfig}.
 */
@RestController
@RequestMapping("/admin/verification")
public class AdminVerificationQueueController {

    /** Server-side cap on the queue: the portal's table takes a bare array with no paging. */
    static final int QUEUE_LIMIT = 200;

    private final VerificationService verificationService;
    private final ProviderDirectoryPort providerDirectory;
    private final CallerIdentity callerIdentity;

    public AdminVerificationQueueController(VerificationService verificationService,
                                            ProviderDirectoryPort providerDirectory,
                                            CallerIdentity callerIdentity) {
        this.verificationService = verificationService;
        this.providerDirectory = providerDirectory;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /admin/verification/queue} — providers whose documents await review
     * ({@code DOCUMENT_SUBMITTED}), oldest submission first, at most {@value #QUEUE_LIMIT}.
     *
     * <p>Names and skills come from the Provider Service in one batch call; if it is unavailable
     * the queue is still returned, with those fields {@code null}.
     */
    @GetMapping("/queue")
    public ResponseEntity<List<VerificationQueueEntryResponse>> queue() {
        List<VerificationQueueRow> rows = verificationService.reviewQueue(QUEUE_LIMIT);
        Map<UUID, ProviderSummary> summaries = rows.isEmpty()
                ? Map.of()
                : providerDirectory.summariesOf(rows.stream().map(VerificationQueueRow::providerId).toList());
        return ResponseEntity.ok(rows.stream()
                .map(row -> VerificationQueueEntryResponse.from(row, summaries.get(row.providerId())))
                .toList());
    }

    /**
     * {@code GET /admin/verification/{providerId}/documents} — the documents on file for a
     * provider. 404 {@code VERIFICATION_NOT_FOUND} when the provider has no verification record.
     */
    @GetMapping("/{providerId}/documents")
    public ResponseEntity<List<VerificationDocumentResponse>> documents(
            @PathVariable("providerId") UUID providerId) {
        return ResponseEntity.ok(verificationService.documentsOf(providerId).stream()
                .map(VerificationDocumentResponse::from)
                .toList());
    }

    /**
     * {@code POST /admin/verification/{providerId}/decision} — approve or reject the submission.
     *
     * <p>{@code APPROVE} is the {@code verify-documents} step (Requirement 5.4): the documents are
     * accepted and the background check starts, so the provider leaves the queue but is not yet
     * {@code APPROVED} for jobs. {@code REJECT} requires a reason (400 {@code VALIDATION_ERROR}
     * otherwise) and notifies the provider (Requirement 5.8). A provider not in a state that allows
     * the decision gets 409 {@code INVALID_STATE_TRANSITION}. Answers 204: the portal reads no body.
     */
    @PostMapping("/{providerId}/decision")
    public ResponseEntity<Void> decide(@PathVariable("providerId") UUID providerId,
                                       @Valid @RequestBody VerificationDecisionRequest request) {
        UUID admin = callerIdentity.requireCallerId();
        switch (request.decision()) {
            case APPROVE -> verificationService.markDocumentsVerified(providerId, admin, request.reason());
            case REJECT -> verificationService.reject(providerId, admin, request.reason());
        }
        return ResponseEntity.noContent().build();
    }
}
