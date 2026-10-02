package com.homefix.verification.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.verification.api.dto.ApprovedProvidersRequest;
import com.homefix.verification.api.dto.ApprovedProvidersResponse;
import com.homefix.verification.api.dto.InternalStatusChangeRequest;
import com.homefix.verification.api.dto.InternalStatusChangeResponse;
import com.homefix.verification.api.dto.VerificationStatusesRequest;
import com.homefix.verification.api.dto.VerificationStatusesResponse;
import com.homefix.verification.service.VerificationService;

import jakarta.validation.Valid;

/**
 * Service-to-service surface for the Provider Service: the batch {@code APPROVED} lookup behind the
 * dispatch eligibility search (Requirements 5.10, 8.2), and the status lookup and suspend /
 * reinstate actions behind the Admin provider list (Requirement 19.2).
 *
 * <p>Reachable only with the shared {@code X-Internal-Api-Key} credential — see
 * {@code InternalApiKeyFilter} and {@code WebSecurityConfig}. The API Gateway does not route
 * {@code /internal/**}, and no RBAC rule covers it: the caller is a service, not a user with roles.
 */
@RestController
@RequestMapping("/internal/verifications")
public class InternalVerificationController {

    private final VerificationService verificationService;

    public InternalVerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * {@code POST /internal/verifications/approved} — which of the given providers are
     * {@code APPROVED}.
     *
     * <p>The batch form of {@code GET /verifications/{providerId}/job-assignment-eligibility}: the
     * Provider Service asks once per dispatch search instead of once per candidate. It is a
     * {@code POST} only because the id list belongs in a body; it reads, never writes. Like the
     * single-provider gate it conveys nothing beyond the eligibility verdict.
     */
    @PostMapping("/approved")
    public ResponseEntity<ApprovedProvidersResponse> approved(@Valid @RequestBody ApprovedProvidersRequest request) {
        List<UUID> approved = verificationService.approvedAmong(request.providerIds()).stream()
                .sorted()
                .toList();
        return ResponseEntity.ok(new ApprovedProvidersResponse(approved));
    }

    /**
     * {@code POST /internal/verifications/statuses} — the current verification status of each of
     * the given providers, for the Provider Service's Admin provider list (Requirement 19.2).
     *
     * <p>One call per list, not one per row. Ids with no verification record are absent from
     * {@code statuses}. A {@code POST} only because the id list belongs in a body; it reads, never
     * writes.
     */
    @PostMapping("/statuses")
    public ResponseEntity<VerificationStatusesResponse> statuses(
            @Valid @RequestBody VerificationStatusesRequest request) {
        Map<UUID, String> statuses = new LinkedHashMap<>();
        verificationService.statusesAmong(request.providerIds())
                .forEach((providerId, status) -> statuses.put(providerId, status.name()));
        return ResponseEntity.ok(new VerificationStatusesResponse(statuses));
    }

    /**
     * {@code POST /internal/verifications/{providerId}/suspend} — suspend an {@code APPROVED}
     * provider on an Admin's behalf (Requirement 5.9), driven by the Provider Service's
     * {@code PATCH /admin/providers/{id}/status}. Same transition, dispatch-pool removal and audit
     * entry as {@code POST /admin/verifications/{providerId}/suspend}; the acting Admin comes from
     * the body because the caller is a service.
     */
    @PostMapping("/{providerId}/suspend")
    public ResponseEntity<InternalStatusChangeResponse> suspend(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody InternalStatusChangeRequest request) {
        return ResponseEntity.ok(InternalStatusChangeResponse.from(
                verificationService.suspend(providerId, request.actorId(), request.reason())));
    }

    /**
     * {@code POST /internal/verifications/{providerId}/reinstate} — reinstate a {@code SUSPENDED}
     * provider to {@code APPROVED} (Requirement 5.1) on an Admin's behalf. 409
     * {@code INVALID_STATE_TRANSITION} for a provider that is not suspended — it never performs a
     * first-time approval.
     */
    @PostMapping("/{providerId}/reinstate")
    public ResponseEntity<InternalStatusChangeResponse> reinstate(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody InternalStatusChangeRequest request) {
        return ResponseEntity.ok(InternalStatusChangeResponse.from(
                verificationService.reinstate(providerId, request.actorId(), request.reason())));
    }
}
