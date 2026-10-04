package com.homefix.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.MobileNumberRequest;
import com.homefix.provider.api.dto.TeamProviderResponse;
import com.homefix.provider.api.dto.TenantAdminResponse;
import com.homefix.provider.api.dto.TenantMembersResponse;
import com.homefix.provider.api.dto.TenantRejectionRequest;
import com.homefix.provider.api.dto.TenantRequest;
import com.homefix.provider.api.dto.TenantResponse;
import com.homefix.provider.service.TenantApplicationService;
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;

import jakarta.validation.Valid;

/**
 * The Admin Portal's Tenants module (Requirements MT-1, MT-2, MT-3, MT-12): the registry, its
 * administrators and its teams, for Platform_Admins. The API Gateway routes
 * {@code /admin/tenants/**} here ahead of its {@code /admin/**} catch-all.
 *
 * <p>Role enforcement (ADMIN / SUPER_ADMIN) is applied by the shared {@code RbacEnforcementFilter}
 * using the rules in {@code ProviderRbacConfig}; a Tenant_Admin is refused with 403
 * (Requirement MT-10.3). The acting Admin's id is read from the principal because every change is
 * recorded against it (Requirement MT-1.3).
 */
@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {

    private final TenantService tenantService;
    private final TenantTeamService teamService;
    private final TenantApplicationService applications;
    private final CallerIdentity callerIdentity;

    public AdminTenantController(TenantService tenantService, TenantTeamService teamService,
                                 TenantApplicationService applications, CallerIdentity callerIdentity) {
        this.tenantService = tenantService;
        this.teamService = teamService;
        this.applications = applications;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /admin/tenants} — every Tenant with provider and admin counts, by name; with
     * {@code ?status=PENDING_APPROVAL} (or any other status) only those (email-auth Requirement 5.6).
     */
    @GetMapping
    public ResponseEntity<List<TenantResponse>> list(@RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(tenantService.list().stream()
                .filter(view -> status == null || status.isBlank()
                        || view.tenant().getStatus().name().equalsIgnoreCase(status.strip()))
                .map(TenantResponse::from)
                .toList());
    }

    /**
     * {@code POST /admin/tenants/{id}/approval} — the agency becomes {@code ACTIVE} and its applicant
     * its administrator; 409 {@code APPLICATION_NOT_PENDING}.
     */
    @PostMapping("/{id}/approval")
    public ResponseEntity<TenantResponse> approve(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TenantResponse.from(applications.approve(id, callerIdentity.requireCallerId())));
    }

    /**
     * {@code POST /admin/tenants/{id}/rejection} — {@code REJECTED} with the reason the applicant sees;
     * 400 {@code REASON_REQUIRED}, 409 {@code APPLICATION_NOT_PENDING}.
     */
    @PostMapping("/{id}/rejection")
    public ResponseEntity<TenantResponse> reject(@PathVariable("id") UUID id,
                                                 @RequestBody TenantRejectionRequest request) {
        return ResponseEntity.ok(TenantResponse.from(
                applications.reject(id, request.reason(), callerIdentity.requireCallerId())));
    }

    /** {@code POST /admin/tenants} — 201 with the new {@code ACTIVE} Tenant; 400 names the failed rule. */
    @PostMapping
    public ResponseEntity<TenantResponse> create(@RequestBody TenantRequest request) {
        UUID actor = callerIdentity.requireCallerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TenantResponse.from(tenantService.create(request.toCommand(), actor)));
    }

    /** {@code PUT /admin/tenants/{id}} — replaces details, area, categories and status atomically. */
    @PutMapping("/{id}")
    public ResponseEntity<TenantResponse> update(@PathVariable("id") UUID id,
                                                 @RequestBody TenantRequest request) {
        UUID actor = callerIdentity.requireCallerId();
        return ResponseEntity.ok(TenantResponse.from(tenantService.update(id, request.toCommand(), actor)));
    }

    /** {@code GET /admin/tenants/{id}/members} — administrators and team. */
    @GetMapping("/{id}/members")
    public ResponseEntity<TenantMembersResponse> members(@PathVariable("id") UUID id) {
        List<TenantAdminResponse> admins = tenantService.admins(id).stream()
                .map(TenantAdminResponse::from).toList();
        List<TeamProviderResponse> providers = teamService.team(id).stream()
                .map(TeamProviderResponse::from).toList();
        return ResponseEntity.ok(new TenantMembersResponse(admins, providers));
    }

    /**
     * {@code POST /admin/tenants/{id}/admins} — grants {@code TENANT_ADMIN} and records the
     * administrator; 404 {@code USER_NOT_FOUND}, 409 {@code ADMIN_OF_OTHER_TENANT}.
     */
    @PostMapping("/{id}/admins")
    public ResponseEntity<TenantAdminResponse> addAdmin(@PathVariable("id") UUID id,
                                                        @Valid @RequestBody MobileNumberRequest request) {
        UUID actor = callerIdentity.requireCallerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TenantAdminResponse.from(tenantService.addAdmin(id, request.mobileNumber(), actor)));
    }

    /** {@code DELETE /admin/tenants/{id}/admins/{userId}} — removes the membership, revokes the role. */
    @DeleteMapping("/{id}/admins/{userId}")
    public ResponseEntity<Void> removeAdmin(@PathVariable("id") UUID id, @PathVariable("userId") UUID userId) {
        tenantService.removeAdmin(id, userId, callerIdentity.requireCallerId());
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /admin/tenants/{id}/providers} — attaches a provider by mobile number; 404
     * {@code PROVIDER_NOT_FOUND}, 409 {@code PROVIDER_IN_OTHER_TENANT}.
     */
    @PostMapping("/{id}/providers")
    public ResponseEntity<TeamProviderResponse> addProvider(@PathVariable("id") UUID id,
                                                            @Valid @RequestBody MobileNumberRequest request) {
        UUID actor = callerIdentity.requireCallerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TeamProviderResponse.from(teamService.addProvider(id, request.mobileNumber(), actor)));
    }

    /** {@code DELETE /admin/tenants/{id}/providers/{providerId}} — detaches a provider. */
    @DeleteMapping("/{id}/providers/{providerId}")
    public ResponseEntity<Void> removeProvider(@PathVariable("id") UUID id,
                                               @PathVariable("providerId") UUID providerId) {
        teamService.removeProvider(id, providerId, callerIdentity.requireCallerId());
        return ResponseEntity.noContent().build();
    }
}
