package com.homefix.admin.rbac;

/**
 * The two Admin-tier roles recognised by the Admin Service (Requirement 19.6).
 *
 * <p>These correspond to the {@code ADMIN} and {@code SUPER_ADMIN} role claims carried in the
 * JWT (Task 4). SUPER_ADMIN is a strict superset of ADMIN's authority.
 */
public enum AdminRole {
    ADMIN,
    SUPER_ADMIN
}
