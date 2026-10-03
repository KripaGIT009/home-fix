package com.homefix.auth.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.admin.AccountRoleService;

/**
 * Service-to-service account lookup and role management, under {@code /internal/users}, for
 * provider-service's Tenant administration (Requirement MT-2.2, MT-2.4).
 *
 * <p>Guarded like {@link InternalUserContactController}: {@code /internal/**} requires the shared
 * service credential in {@code X-Internal-Api-Key} (see {@code InternalApiKeyFilter}) and the
 * {@code ROLE_INTERNAL} authority only that filter grants, and the API Gateway does not route it.
 * An end-user token, even a SUPER_ADMIN's, never reaches these handlers.
 *
 * <p>Nothing here logs the mobile number (Requirement 26.4).
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserRoleController {

    private final AccountRoleService accountRoleService;

    public InternalUserRoleController(AccountRoleService accountRoleService) {
        this.accountRoleService = accountRoleService;
    }

    /**
     * {@code GET /internal/users/by-mobile?mobileNumber=+91…} — the account registered with that
     * number.
     *
     * @return 200 {@link InternalUserResponse}; 404 {@code USER_NOT_FOUND} when no account has it
     */
    @GetMapping("/by-mobile")
    public ResponseEntity<InternalUserResponse> byMobile(@RequestParam("mobileNumber") String mobileNumber) {
        return accountRoleService.findByMobile(mobileNumber)
                .map(InternalUserResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UserNotFoundException(null));
    }

    /**
     * {@code POST /internal/users/{userId}/roles/{role}} — grant; idempotent. Only
     * {@code TENANT_ADMIN}.
     *
     * @return 200 with the account after the change; 400 {@code ROLE_NOT_MANAGEABLE}; 404
     *         {@code USER_NOT_FOUND}
     */
    @PostMapping("/{userId}/roles/{role}")
    public ResponseEntity<InternalUserResponse> grant(@PathVariable("userId") UUID userId,
                                                      @PathVariable("role") String role) {
        return ResponseEntity.ok(InternalUserResponse.from(accountRoleService.grant(userId, role)));
    }

    /**
     * {@code DELETE /internal/users/{userId}/roles/{role}} — revoke and end the account's
     * sessions; idempotent. Only {@code TENANT_ADMIN}.
     *
     * @return 200 with the account after the change; 400 {@code ROLE_NOT_MANAGEABLE}; 404
     *         {@code USER_NOT_FOUND}
     */
    @DeleteMapping("/{userId}/roles/{role}")
    public ResponseEntity<InternalUserResponse> revoke(@PathVariable("userId") UUID userId,
                                                       @PathVariable("role") String role) {
        return ResponseEntity.ok(InternalUserResponse.from(accountRoleService.revoke(userId, role)));
    }
}
