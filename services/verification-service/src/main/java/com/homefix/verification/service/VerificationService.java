package com.homefix.verification.service;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.verification.backgroundcheck.BackgroundCheckPort;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.DispatchPoolPort;
import com.homefix.verification.domain.BackgroundCheckQueueRow;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationDocument;
import com.homefix.verification.domain.VerificationQueueRow;
import com.homefix.verification.domain.VerificationRepository;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.domain.VerificationStatusView;
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

    /** The background-check step: started, or result recorded but not yet decided. */
    private static final Set<VerificationStatus> BACKGROUND_CHECK_STAGE = EnumSet.of(
            VerificationStatus.BACKGROUND_CHECK_PENDING, VerificationStatus.BACKGROUND_CHECK_COMPLETED);

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

        Verification verification = repository.findWithDocumentsByProviderId(providerId)
                .orElseGet(() -> repository.save(Verification.create(providerId)));

        for (DocumentUpload upload : uploads) {
            String ref = documentStorage.store(providerId, upload.type(), upload.content(), upload.contentType());
            verification.addDocument(upload.type(), ref, upload.contentType(),
                    upload.content() == null ? 0 : upload.content().length);
        }

        verification.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, actorId,
                "Provider submitted required documents");
        return fullyLoaded(repository.save(verification));
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
        return fullyLoaded(repository.save(verification));
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
        return fullyLoaded(repository.save(verification));
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
        return fullyLoaded(repository.save(verification));
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
        Verification saved = fullyLoaded(repository.save(verification));
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
        Verification saved = fullyLoaded(repository.save(verification));
        dispatchPool.deactivateProvider(providerId);
        return saved;
    }

    /**
     * Reinstates a {@code SUSPENDED} provider to {@code APPROVED} (Requirement 5.1:
     * {@code SUSPENDED → APPROVED}), restoring dispatch eligibility through the APPROVED gate.
     *
     * <p>Distinct from {@link #approve} on purpose: {@code approve} also performs the first-time
     * approval from {@code BACKGROUND_CHECK_COMPLETED}, so an Admin who merely asked to
     * "re-activate" a provider whose background check had just completed would otherwise approve
     * them as a side effect. This method only ever undoes a suspension.
     *
     * @throws VerificationException 409 {@code INVALID_STATE_TRANSITION} when the provider is not
     *         currently {@code SUSPENDED}
     */
    @Transactional
    public Verification reinstate(UUID providerId, UUID adminId, String reason) {
        Verification verification = require(providerId);
        if (verification.getStatus() != VerificationStatus.SUSPENDED) {
            throw new VerificationException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "Only a SUSPENDED provider can be reinstated",
                    List.of("currentState=" + verification.getStatus(),
                            "disallowedTarget=" + VerificationStatus.APPROVED));
        }
        verification.transitionTo(VerificationStatus.APPROVED, adminId,
                reason == null ? "Admin reinstated provider" : reason);
        return fullyLoaded(repository.save(verification));
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
     * The subset of {@code providerIds} currently {@code APPROVED} — the batch form of
     * {@link #canReceiveJobAssignment(UUID)}, for the dispatch eligibility search (Requirements
     * 5.10, 8.2). Ids with no verification record are simply absent from the result, exactly as
     * they are not eligible one at a time.
     */
    @Transactional(readOnly = true)
    public Set<UUID> approvedAmong(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(repository.findProviderIdsByStatus(
                new HashSet<>(providerIds), VerificationStatus.APPROVED));
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

    /**
     * The Admin review queue (Requirement 19.3): verifications in {@code DOCUMENT_SUBMITTED},
     * oldest submission first, at most {@code limit} of them. A projection — no aggregate loaded.
     */
    @Transactional(readOnly = true)
    public List<VerificationQueueRow> reviewQueue(int limit) {
        return repository.findQueue(VerificationStatus.DOCUMENT_SUBMITTED, PageRequest.of(0, limit));
    }

    /**
     * Providers whose background check was started and who await the admin's decision
     * ({@code BACKGROUND_CHECK_PENDING} or {@code BACKGROUND_CHECK_COMPLETED}), oldest check first.
     */
    @Transactional(readOnly = true)
    public List<BackgroundCheckQueueRow> backgroundCheckQueue(int limit) {
        return repository.findBackgroundCheckQueue(BACKGROUND_CHECK_STAGE, PageRequest.of(0, limit));
    }

    /**
     * Records the background-check result an admin received and decides in one step
     * (Requirement 5.6-5.8): {@code BACKGROUND_CHECK_PENDING → BACKGROUND_CHECK_COMPLETED}, then
     * {@code APPROVED} (the provider may take jobs) or {@code REJECTED} (the provider is notified with
     * the reason). A provider whose result was already recorded is only decided, with the result
     * replaced by this one.
     *
     * <p>No background-check vendor is integrated yet ({@code LoggingBackgroundCheckAdapter}), so this
     * is how a check is completed: the admin runs it outside the platform and records the outcome.
     *
     * @throws VerificationException 400 {@code VALIDATION_ERROR} without a result, or rejecting
     *         without a reason; 404 for an unknown provider; 409 {@code INVALID_STATE_TRANSITION}
     *         when the provider is not at the background-check step
     */
    @Transactional
    public Verification decideBackgroundCheck(UUID providerId, UUID adminId, boolean passed, String result,
                                              String reason) {
        if (result == null || result.isBlank()) {
            throw VerificationException.validation("The background check result is required");
        }
        if (!passed && (reason == null || reason.isBlank())) {
            throw VerificationException.validation("A rejection reason is required");
        }
        Verification verification = require(providerId);
        if (!BACKGROUND_CHECK_STAGE.contains(verification.getStatus())) {
            throw new VerificationException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "This provider is not waiting on a background check",
                    List.of("currentState=" + verification.getStatus()));
        }
        verification.recordBackgroundCheckResult(result.strip());
        if (verification.getStatus() == VerificationStatus.BACKGROUND_CHECK_PENDING) {
            verification.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, adminId,
                    "Background check result recorded by admin");
        }
        if (passed) {
            verification.transitionTo(VerificationStatus.APPROVED, adminId,
                    reason == null || reason.isBlank() ? "Background check passed" : reason.strip());
            return fullyLoaded(repository.save(verification));
        }
        verification.transitionTo(VerificationStatus.REJECTED, adminId, reason.strip());
        Verification saved = fullyLoaded(repository.save(verification));
        notification.notifyRejected(providerId, reason.strip());
        return saved;
    }

    /**
     * The documents on file for a provider, for the Admin document viewer (Requirement 19.3).
     *
     * @throws VerificationException 404 when the provider has no verification record
     */
    @Transactional(readOnly = true)
    public List<VerificationDocument> documentsOf(UUID providerId) {
        // require() fetch-joins the documents; copy them out while the transaction is open.
        return List.copyOf(require(providerId).getDocuments());
    }

    /**
     * The current verification status of each of {@code providerIds} that has a record — the
     * batch lookup behind the Admin provider list (Requirement 19.2). Ids with no record are
     * absent, which the caller reads as "has not submitted yet". One statement however many ids.
     */
    @Transactional(readOnly = true)
    public Map<UUID, VerificationStatus> statusesAmong(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VerificationStatus> statuses = new LinkedHashMap<>();
        for (VerificationStatusView view : repository.findStatusesByProviderIds(new HashSet<>(providerIds))) {
            statuses.put(view.providerId(), view.status());
        }
        return statuses;
    }

    @Transactional(readOnly = true)
    public Verification getByProviderId(UUID providerId) {
        return fullyLoaded(require(providerId));
    }

    /** Loads the aggregate with its documents fetch-joined; the audit trail is still lazy. */
    private Verification require(UUID providerId) {
        return repository.findWithDocumentsByProviderId(providerId)
                .orElseThrow(() -> VerificationException.notFound(
                        "No verification record for provider " + providerId));
    }

    /**
     * Initialises both lazy collections while the transaction is still open, so the controllers
     * can map the returned aggregate to a response after it has closed ({@code open-in-view} is
     * off). Each collection costs at most one SELECT — the documents are usually already
     * fetch-joined by {@link #require} — never one per element, and they are loaded separately
     * rather than joined together, so there is no documents x audit-entries product.
     */
    private static Verification fullyLoaded(Verification verification) {
        Hibernate.initialize(verification.getDocuments());
        Hibernate.initialize(verification.getAuditTrail());
        return verification;
    }
}
