package com.homefix.auth.admin;

import java.util.Set;
import java.util.UUID;

import com.homefix.auth.domain.Role;

/**
 * The staff member performing an Admin Portal action: their account id (the JWT subject) and the
 * role names their token carries. The shared {@code RbacEnforcementFilter} has already required
 * ADMIN or SUPER_ADMIN before a request reaches the service; the finer rules in
 * {@link AdminUserService} (no self-change, SUPER_ADMIN for administrator accounts) need to know
 * exactly who is asking.
 *
 * @param userId the caller's account id
 * @param roles  the caller's role names, without the {@code ROLE_} authority prefix
 */
public record StaffActor(UUID userId, Set<String> roles) {

    public StaffActor {
        roles = Set.copyOf(roles);
    }

    public boolean isSuperAdmin() {
        return roles.contains(Role.SUPER_ADMIN.name());
    }
}
