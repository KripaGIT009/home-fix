package com.homefix.verification.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.verification.api.dto.AdminActionRequest;
import com.homefix.verification.api.dto.BackgroundCheckResultRequest;
import com.homefix.verification.api.dto.VerificationResponse;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.service.VerificationService;

import jakarta.validation.Valid;

/**
 * Admin-facing verification workflow endpoints (Requirement 5.4–5.9, 5.11).
 *
 * <p>Each action drives one step of the verification state machine and records an audit entry
 * with the acting Admin's user ID (taken from the authenticated principal), a timestamp, the
 * previous state, the new state, and a reason (Requirement 5.11). Fine-grained role
 * enforcement (ADMIN / SUPER_ADMIN) is applied by the shared {@code RbacEnforcementFilter}
 * (Task 4); this controller only requires an authenticated principal.
 */
@RestController
@RequestMapping("/admin/verifications/{providerId}")
public class AdminVerificationController {

    private final VerificationService verificationService;

    public AdminVerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * {@code POST /admin/verifications/{providerId}/verify-documents} — mark documents
     * verified and trigger the background check (Requirement 5.4, 5.5).
     */
    @PostMapping("/verify-documents")
    public ResponseEntity<VerificationResponse> markDocumentsVerified(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody(required = false) AdminActionRequest request) {
        Verification v = verificationService.markDocumentsVerified(providerId, currentActor(), reason(request));
        return ResponseEntity.ok(VerificationResponse.from(v));
    }

    /**
     * {@code POST /admin/verifications/{providerId}/background-check-result} — record a
     * received background-check result and move to {@code BACKGROUND_CHECK_COMPLETED}
     * (Requirement 5.6).
     */
    @PostMapping("/background-check-result")
    public ResponseEntity<VerificationResponse> completeBackgroundCheck(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody BackgroundCheckResultRequest request) {
        Verification v = verificationService.completeBackgroundCheck(
                providerId, currentActor(), request.result());
        return ResponseEntity.ok(VerificationResponse.from(v));
    }

    /**
     * {@code POST /admin/verifications/{providerId}/approve} — approve a provider with
     * {@code BACKGROUND_CHECK_COMPLETED} status, or reinstate a {@code SUSPENDED} provider
     * (Requirement 5.7, 5.1).
     */
    @PostMapping("/approve")
    public ResponseEntity<VerificationResponse> approve(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody(required = false) AdminActionRequest request) {
        Verification v = verificationService.approve(providerId, currentActor(), reason(request));
        return ResponseEntity.ok(VerificationResponse.from(v));
    }

    /**
     * {@code POST /admin/verifications/{providerId}/reject} — reject a provider, recording the
     * (required) reason and notifying the provider via the Notification Service
     * (Requirement 5.8).
     */
    @PostMapping("/reject")
    public ResponseEntity<VerificationResponse> reject(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody AdminActionRequest request) {
        Verification v = verificationService.reject(providerId, currentActor(), reason(request));
        return ResponseEntity.ok(VerificationResponse.from(v));
    }

    /**
     * {@code POST /admin/verifications/{providerId}/suspend} — suspend an APPROVED provider,
     * remove them from the active dispatch pool, and cancel pending job offers
     * (Requirement 5.9).
     */
    @PostMapping("/suspend")
    public ResponseEntity<VerificationResponse> suspend(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody(required = false) AdminActionRequest request) {
        Verification v = verificationService.suspend(providerId, currentActor(), reason(request));
        return ResponseEntity.ok(VerificationResponse.from(v));
    }

    /**
     * @return the authenticated Admin's user ID, taken from the principal name populated by the
     *         shared {@code JwtValidationFilter} (Task 4).
     * @throws VerificationException 401 when there is no authenticated principal, or when the
     *         principal name is not a UUID.
     */
    private UUID currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new VerificationException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "An authenticated Admin is required to perform verification actions");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new VerificationException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "Authenticated principal is not a valid user ID");
        }
    }

    private static String reason(AdminActionRequest request) {
        return request == null ? null : request.reason();
    }
}
