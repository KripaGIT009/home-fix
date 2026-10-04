package com.homefix.provider.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.TenantApplicationResponse;
import com.homefix.provider.api.dto.TenantRequest;
import com.homefix.provider.service.TenantApplicationService;

/**
 * Agency self-registration from the Admin Portal (email-auth Requirement 5). Any signed-in user may
 * call it, so it has no RBAC rule; the applicant is always the caller, never a request field.
 */
@RestController
@RequestMapping("/tenant-applications")
public class TenantApplicationController {

    private final TenantApplicationService applications;
    private final CallerIdentity callerIdentity;

    public TenantApplicationController(TenantApplicationService applications, CallerIdentity callerIdentity) {
        this.applications = applications;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code POST /tenant-applications} — 201 with the {@code PENDING_APPROVAL} application; 409
     * {@code APPLICATION_EXISTS}; 400 naming a failed rule. A {@code status} in the body is ignored.
     */
    @PostMapping
    public ResponseEntity<TenantApplicationResponse> apply(@RequestBody TenantRequest request) {
        UUID applicant = callerIdentity.requireCallerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantApplicationResponse.from(
                applications.apply(applicant, request.toCommand()).tenant()));
    }

    /** {@code GET /tenant-applications/me} — the caller's latest application; 404 if none. */
    @GetMapping("/me")
    public TenantApplicationResponse mine() {
        return TenantApplicationResponse.from(applications.mine(callerIdentity.requireCallerId()));
    }
}
