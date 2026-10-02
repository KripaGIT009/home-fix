package com.homefix.auth.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.auth.admin.AdminUserException;
import com.homefix.auth.admin.AdminUserService;
import com.homefix.auth.admin.StaffActor;

import jakarta.validation.Valid;

/**
 * Admin Portal User Management (Requirement 19.2), at the paths the portal calls. The API
 * Gateway routes {@code /admin/users/**} here unchanged.
 *
 * <p>Both endpoints are ADMIN / SUPER_ADMIN only, enforced by the shared
 * {@code RbacEnforcementFilter} over the rules in {@code AuthRbacConfig}; the per-target rules for
 * status changes are in {@link AdminUserService}.
 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * {@code GET /admin/users?search=} — accounts whose mobile number or username contains the
     * search term (case-insensitive), newest first, at most 200. A bare array: the portal's table
     * does not page.
     */
    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> list(
            @RequestParam(value = "search", required = false) String search) {
        return ResponseEntity.ok(adminUserService.search(search).stream()
                .map(AdminUserResponse::from)
                .toList());
    }

    /**
     * {@code PATCH /admin/users/{id}/status} — suspend, deactivate or reactivate an account.
     *
     * @return 200 with the updated account; 404 {@code USER_NOT_FOUND}; 403
     *         {@code SELF_STATUS_CHANGE_FORBIDDEN} or {@code SUPER_ADMIN_REQUIRED}
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserResponse> updateStatus(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(AdminUserResponse.from(
                adminUserService.changeStatus(id, request.status(), currentActor())));
    }

    /**
     * The caller as the JWT validation filter left them in the security context: the subject as
     * the account id, the {@code ROLE_*} authorities as role names.
     */
    private static StaffActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw AdminUserException.invalidPrincipal();
        }
        UUID actorId;
        try {
            actorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw AdminUserException.invalidPrincipal();
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toSet());
        return new StaffActor(actorId, roles);
    }
}
