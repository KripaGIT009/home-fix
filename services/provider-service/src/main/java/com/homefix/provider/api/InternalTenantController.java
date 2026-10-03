package com.homefix.provider.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.CoveringTenantsResponse;
import com.homefix.provider.api.dto.ProviderTenantResponse;
import com.homefix.provider.api.dto.TenantMembershipResponse;
import com.homefix.provider.api.dto.TenantRefResponse;
import com.homefix.provider.service.ProviderException;
import com.homefix.provider.service.TenantService;
import com.homefix.provider.service.TenantTeamService;

/**
 * Service-to-service Tenant lookups for booking-service (Requirements MT-4.1, MT-5.2, MT-8.1,
 * MT-10.5): coverage for the fallback, the caller's Tenant for the Tenant endpoints, the
 * assignability check, a provider's Tenant on automatic acceptance, and a Tenant's name for the
 * booking detail.
 *
 * <p>Reachable only with the shared {@code X-Internal-Api-Key} credential — see
 * {@code InternalApiKeyFilter} and {@code WebSecurityConfig} — and not routed by the API Gateway.
 * A 404 is an answer ("not an admin", "not a member", "independent"), never an outage, so callers
 * can tell the two apart.
 */
@RestController
@RequestMapping("/internal/tenants")
public class InternalTenantController {

    private final TenantService tenantService;
    private final TenantTeamService teamService;

    public InternalTenantController(TenantService tenantService, TenantTeamService teamService) {
        this.tenantService = tenantService;
        this.teamService = teamService;
    }

    /**
     * {@code GET /internal/tenants/covering?lat&lon&categoryId} — the {@code ACTIVE} Tenants whose
     * area and categories cover the point, nearest first; an empty list when none does.
     */
    @GetMapping("/covering")
    public ResponseEntity<CoveringTenantsResponse> covering(@RequestParam("lat") double lat,
                                                            @RequestParam("lon") double lon,
                                                            @RequestParam("categoryId") UUID categoryId) {
        return ResponseEntity.ok(CoveringTenantsResponse.from(tenantService.covering(lat, lon, categoryId)));
    }

    /** {@code GET /internal/tenants/by-admin/{userId}} — 404 {@code TENANT_NOT_FOUND} when not an admin. */
    @GetMapping("/by-admin/{userId}")
    public ResponseEntity<TenantRefResponse> byAdmin(@PathVariable("userId") UUID userId) {
        return ResponseEntity.ok(TenantRefResponse.from(tenantService.byAdmin(userId)
                .orElseThrow(() -> tenantNotFound("User " + userId + " administers no Tenant"))));
    }

    /**
     * {@code GET /internal/tenants/{tenantId}/providers/{providerId}} — the provider's standing on
     * that Tenant's team; 404 {@code PROVIDER_NOT_FOUND} when not a member.
     */
    @GetMapping("/{tenantId}/providers/{providerId}")
    public ResponseEntity<TenantMembershipResponse> membership(@PathVariable("tenantId") UUID tenantId,
                                                               @PathVariable("providerId") UUID providerId) {
        return ResponseEntity.ok(TenantMembershipResponse.from(teamService.membership(tenantId, providerId)
                .orElseThrow(() -> ProviderException.notFound(
                        "Provider " + providerId + " is not a member of Tenant " + tenantId))));
    }

    /** {@code GET /internal/tenants/of-provider/{providerId}} — 404 {@code TENANT_NOT_FOUND} when independent. */
    @GetMapping("/of-provider/{providerId}")
    public ResponseEntity<ProviderTenantResponse> ofProvider(@PathVariable("providerId") UUID providerId) {
        return ResponseEntity.ok(ProviderTenantResponse.from(tenantService.ofProvider(providerId)
                .orElseThrow(() -> tenantNotFound("Provider " + providerId + " belongs to no Tenant"))));
    }

    /** {@code GET /internal/tenants/{tenantId}} — 404 {@code TENANT_NOT_FOUND} when unknown. */
    @GetMapping("/{tenantId}")
    public ResponseEntity<TenantRefResponse> tenant(@PathVariable("tenantId") UUID tenantId) {
        return ResponseEntity.ok(TenantRefResponse.from(tenantService.find(tenantId)
                .orElseThrow(() -> tenantNotFound("Tenant " + tenantId + " not found"))));
    }

    private static ProviderException tenantNotFound(String message) {
        return new ProviderException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", message);
    }
}
