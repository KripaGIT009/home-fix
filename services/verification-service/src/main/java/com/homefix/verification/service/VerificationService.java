package com.homefix.verification.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationRepository;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.notification.ProviderNotificationPort;
import com.homefix.verification.storage.DocumentStoragePort;

/**
 * Core verification-workflow business logic (Requirement 5).
 *
 * <p>Enforces the verification state machine on every status change (Requirement 5.1, 5.2,
 * Property 24) via {@link Verification#transitionTo}, uploads documents to encrypted object
 * storage (Requirement 5.3), coordinates the Admin approval workflow (Requirement 5.4–5.9),
 * blocks job assignment for non-APPROVED providers (Requirement 5.10), and records a
 * contiguous audit trail (Requirement 5.11).
 *
 * <p>External dependencies are expressed as ports ({@link DocumentStoragePort},
 * {@link BackgroundCheckPort}, {@link ProviderNotificationPort}, {@link DispatchPoolPort}) so
 * the logic is fully unit-testable.
 */
@Service
public class VerificationService {

    private final VerificationRepository repository;
    private final DocumentStoragePort documentStorage;
    private final BackgroundCheckPort backgroundCheck;
    private final ProviderNotificationPort notification;
    private final DispatchPoolPort dispatchPool;
    private final VerificationProperties properties;

    public VerificationService(VerificationRepository repository,
                               DocumentStoragePort documentStorage,
                               BackgroundCheckPort backgroundCheck,
                               ProviderNotificationPort notification,
                               DispatchPoolPort dispatchPool,
                               VerificationProperties properties) {
        this.repository = repository;
        this.documentStorage = documentStorage;
        this.backgroundCheck = backgroundCheck;
        this.notification = notification;
        this.dispatchPool = dispatchPool;
        this.properties = properties;
    }

    // ============================= Document Upload (Req 5.3) =====================

    /**
     * Uploads the provider's required documents to encrypted object storage and transitions
     * the verification to {@code DOCUMENT_SUBMITTED} (Requirement 5.3). Creates the
     * verification record in {@code PENDING} on first submission.
     *
     * @param providerId the provider submitting documents
     * @param uploads    the documents to store; must cover all required document types
     * @param actorId    the actor performing the action (the provider)
     */
    @Transactional
    public Verification submitDocuments(UUID providerId, List<DocumentUpload> uploads, UUID actorId) {
        if (uploads == null || uploads.isEmpty()) {
            throw VerificationException.validation("At least one document is required");
        }
        Set<DocumentType> provided = uploads.stream()
                .map(DocumentUpload::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DocumentType.class)));
        Set<DocumentType> required = properties.getRequiredDocumentTypes().stream()
                .map(DocumentType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DocumentType.class)));
        if (!provided.containsAll(required)) {
            Set<DocumentType> missing = EnumSet.copyOf(required);
            missing.removeAll(provided);
            throw new VerificationException(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_DOCUMENTS",
                    "Required documents are missing: " + missing);
        }

        Verification verification = repository.findByProviderId(providerId)
                .orElseGet(() -> repository.save(Verification.create(providerId)));

        for (DocumentUpload upload : uploads) {
            String ref = documentStorage.store(providerId, upload.type(), upload.content(), upload.contentType());
            verification.addDocument(upload.type(), ref, upload.contentType(),
                    upload.content() == null ? 0 : upload.content().length);
        }

        verification.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, actorId,
                "Provider submitted required documents");
        return repository.save(verification);
    }

    // ============================= Admin Actions (Req 5.4–5.9) ===================

    /**
     * Marks documents verified and triggers a background check (Requirement 5.4). Transitions
     * {@code DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED} then automatically initiates the
     * background check and moves to {@code BACKGROUND_CHECK_PENDING} (Requirement 5.5).
     */
    @Transactional
    public Verification markDocumentsVerified(UUID providerId, UUID adminId, String reason) {
        Verification verification = require(providerId);
        verification.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, adminId,
                reason == null ? "Admin verified documents" : reason);
        // Requirement 5.4 also requires triggering a background check on verification.
        backgroundCheck.initiate(providerId);
        verification.recordBackgroundCheckStarted(Instant.now());
        verification.transitionTo(VerificationStatus.BACKGROUND_CHECK_PENDING, adminId,
                "Background check initiated");
        return repository.save(verification);
    }

    /**
     * Records a received background-check result and moves to
     * {@code BACKGROUND_CHECK_COMPLETED} (Requirement 5.6).
     */
    @Transactional
    public Verification completeBackgroundCheck(UUID providerId, UUID actorId, String result) {
        Verification verification = require(providerId);
        verification.recordBackgroundCheckResult(result);
        verification.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, actorId,
                "Background check completed");
        return repository.save(verification);
    }

    /**
     * Approves a provider with {@code BACKGROUND_CHECK_COMPLETED} status, enabling job
     * assignments (Requirement 5.7). Also used to reinstate a {@code SUSPENDED} provider
     * (Requirement 5.1: {@code SUSPENDED → APPROVED}).
     */
    @Transactional
    public Verification approve(UUID providerId, UUID adminId, String reason) {
        Verification verification = require(providerId);
        verification.transitionTo(VerificationStatus.APPROVED, adminId,
                reason == null ? "Admin approved provider" : reason);
        return repository.save(verification);
    }

    /**
     * Rejects a provider, records the reason, and notifies the provider via the Notification
     * Service (Requirement 5.8).
     */
    @Transactional
    public Verification reject(UUID providerId, UUID adminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw VerificationException.validation("A rejection reason is required");
        }
        Verification verification = require(providerId);
        verification.transitionTo(VerificationStatus.REJECTED, adminId, reason);
        Verification saved = repository.save(verification);
        notification.notifyRejected(providerId, reason);
        return saved;
    }

    /**
     * Suspends an APPROVED provider, removes them from the active dispatch pool, and cancels
     * pending job offers (Requirement 5.9).
     */
    @Transactional
    public Verification suspend(UUID providerId, UUID adminId, String reason) {
        Verification verification = require(providerId);
        verification.transitionTo(VerificationStatus.SUSPENDED, adminId,
                reason == null ? "Admin suspended provider" : reason);
        Verification saved = repository.save(verification);
        dispatchPool.deactivateProvider(providerId);
        return saved;
    }

    // ============================= Job Assignment Gate (Req 5.10) ================

    /**
     * @return {@code true} iff the provider's current verification status is {@code APPROVED}.
     */
    @Transactional(readOnly = true)
    public boolean canReceiveJobAssignment(UUID providerId) {
        return repository.findByProviderId(providerId)
                .map(v -> v.getStatus() == VerificationStatus.APPROVED)
                .orElse(false);
    }

    /**
     * Asserts that the provider may receive a job assignment, throwing a 403 error otherwise
     * (Requirement 5.10).
     */
    @Transactional(readOnly = true)
    public void assertCanReceiveJobAssignment(UUID providerId) {
        VerificationStatus status = repository.findByProviderId(providerId)
                .map(Verification::getStatus)
                .orElse(null);
        if (status != VerificationStatus.APPROVED) {
            throw new VerificationException(HttpStatus.FORBIDDEN, "PROVIDER_NOT_APPROVED",
                    "Provider is not APPROVED and cannot receive job assignments",
                    List.of("providerId=" + providerId,
                            "currentState=" + (status == null ? "NONE" : status)));
        }
    }

    // ============================= Reads =========================================

    @Transactional(readOnly = true)
    public Verification getByProviderId(UUID providerId) {
        return require(providerId);
    }

    private Verification require(UUID providerId) {
        return repository.findByProviderId(providerId)
                .orElseThrow(() -> VerificationException.notFound(
                        "No verification record for provider " + providerId));
    }
}
