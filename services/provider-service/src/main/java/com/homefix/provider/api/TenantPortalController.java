package com.homefix.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.MobileNumberRequest;
import com.homefix.provider.api.dto.TeamProviderResponse;
import com.homefix.provider.api.dto.TenantResponse;
import com.homefix.provider.domain.Tenant;
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;

import jakarta.validation.Valid;

/**
 * The Tenant Portal's own-agency endpoints for a Tenant_Admin (Requirements MT-3, MT-8.4, MT-11):
 * the caller's Tenant and its team. The API Gateway routes {@code /tenant/me} and
 * {@code /tenant/providers/**} here ({@code /tenant/bookings/**} goes to booking-service).
 *
 * <p>No path or parameter names a Tenant: every request is scoped to the Tenant the caller
 * administers, resolved from the JWT subject (Requirement MT-10.1, Property MT5), so another
 * Tenant's provider is simply not found (404). A suspended Tenant's administrators get 403
 * {@code TENANT_SUSPENDED} on every action (Requirement MT-1.4). The {@code TENANT_ADMIN} role
 * itself is enforced by {@code ProviderRbacConfig}.
 */
@RestController
@RequestMapping("/tenant")
public class TenantPortalController {

    private final TenantService tenantService;
    private final TenantTeamService teamService;
    private final CallerIdentity callerIdentity;

    public TenantPortalController(TenantService tenantService, TenantTeamService teamService,
                                  CallerIdentity callerIdentity) {
        this.tenantService = tenantService;
        this.teamService = teamService;
        this.callerIdentity = callerIdentity;
    }

    /** {@code GET /tenant/me} — the caller's Tenant. */
    @GetMapping("/me")
    public ResponseEntity<TenantResponse> me() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(TenantResponse.from(tenantService.get(tenant.getId())));
    }

    /** {@code GET /tenant/providers} — the team with availability and assignability. */
    @GetMapping("/providers")
    public ResponseEntity<List<TeamProviderResponse>> providers() {
        Tenant tenant = tenantService.requirePortalTenant(callerIdentity.requireCallerId());
        return ResponseEntity.ok(teamService.team(tenant.getId()).stream()
                .map(TeamProviderResponse::from).toList());
    }

    /** {@code POST /tenant/providers} — adds a provider by mobile number (errors of Requirement MT-3.2). */
    @PostMapping("/providers")
    public ResponseEntity<TeamProviderResponse> addProvider(@Valid @RequestBody MobileNumberRequest request) {
        UUID caller = callerIdentity.requireCallerId();
        Tenant tenant = tenantService.requirePortalTenant(caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamProviderResponse.from(
                teamService.addProvider(tenant.getId(), request.mobileNumber(), caller)));
    }

    /** {@code DELETE /tenant/providers/{providerId}} — removes one of the caller's own providers. */
    @DeleteMapping("/providers/{providerId}")
    public ResponseEntity<Void> removeProvider(@PathVariable("providerId") UUID providerId) {
        UUID caller = callerIdentity.requireCallerId();
        Tenant tenant = tenantService.requirePortalTenant(caller);
        teamService.removeProvider(tenant.getId(), providerId, caller);
        return ResponseEntity.noContent().build();
    }
}
