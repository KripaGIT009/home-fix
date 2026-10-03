package com.homefix.provider.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.provider.api.dto.AdminProviderResponse;
import com.homefix.provider.api.dto.BankAccountResponse;
import com.homefix.provider.api.dto.ProviderStatusRequest;
import com.homefix.provider.service.ProviderAdminService;

import jakarta.validation.Valid;

/**
 * The Admin Portal's Provider Management endpoints (Requirement 19.2), at the paths the portal
 * calls; the API Gateway routes {@code /admin/providers} here unchanged.
 *
 * <p>Role enforcement (ADMIN / SUPER_ADMIN; FINANCE_ADMIN additionally for bank-account
 * verification) is applied by the shared {@code RbacEnforcementFilter}
 * using the rules in {@code ProviderRbacConfig}. These endpoints are staff-only by role, so no
 * per-provider ownership assertion applies; the acting Admin's id is still read from the principal
 * because a status change is recorded against it in the verification audit trail
 * (Requirement 5.11).
 */
@RestController
@RequestMapping("/admin/providers")
public class AdminProviderController {

    private final ProviderAdminService adminService;
    private final CallerIdentity callerIdentity;

    public AdminProviderController(ProviderAdminService adminService, CallerIdentity callerIdentity) {
        this.adminService = adminService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /admin/providers?search=} — providers newest first, at most
     * {@value ProviderAdminService#ADMIN_LIST_LIMIT}, optionally filtered by a case-insensitive
     * substring of the display name. A bare array: the portal's table does not page.
     */
    @GetMapping
    public ResponseEntity<List<AdminProviderResponse>> list(
            @RequestParam(name = "search", required = false) String search) {
        return ResponseEntity.ok(adminService.list(search).stream()
                .map(AdminProviderResponse::from)
                .toList());
    }

    /**
     * {@code PATCH /admin/providers/{id}/status} — suspend ({@code SUSPENDED}) or reinstate
     * ({@code ACTIVE}) a provider through the Verification Service, answering the updated provider.
     * {@code DEACTIVATED} is refused with 400 {@code UNSUPPORTED_PROVIDER_STATUS}; a provider not in
     * a state that allows the change gets the Verification Service's 409
     * {@code INVALID_STATE_TRANSITION}.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminProviderResponse> changeStatus(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ProviderStatusRequest request) {
        UUID admin = callerIdentity.requireCallerId();
        return ResponseEntity.ok(AdminProviderResponse.from(
                adminService.changeStatus(id, request.status(), admin)));
    }

    /**
     * {@code POST /admin/providers/{id}/bank-account/verification} — marks the provider's bank
     * account verified so it can receive settlements (Requirement 14.2), answering the account
     * masked. 404 {@code BANK_ACCOUNT_NOT_FOUND} when the provider has none on file.
     */
    @PostMapping("/{id}/bank-account/verification")
    public ResponseEntity<BankAccountResponse> verifyBankAccount(@PathVariable("id") UUID id) {
        UUID actor = callerIdentity.requireCallerId();
        return ResponseEntity.ok(BankAccountResponse.from(id, adminService.verifyBankAccount(id, actor)));
    }
}
