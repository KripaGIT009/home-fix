package com.homefix.auth.admin;

import org.springframework.http.HttpStatus;

/**
 * Refusal of an Admin Portal user-management action, or of an internal role change
 * ({@link AccountRoleService}), carrying the HTTP status and the stable
 * error code the REST layer surfaces through the shared {@code ErrorResponseDto}, like the other
 * domain exceptions in this service. An unknown user id is not one of these: it is the existing
 * 404 {@code UserNotFoundException}.
 */
public class AdminUserException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private AdminUserException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /**
     * A staff member tried to change their own account's status. Suspending yourself locks you
     * out with no way back, and reactivating yourself is never needed while you can sign in.
     */
    public static AdminUserException selfStatusChange() {
        return new AdminUserException(HttpStatus.FORBIDDEN, "SELF_STATUS_CHANGE_FORBIDDEN",
                "You cannot change the status of your own account.");
    }

    /**
     * An ADMIN tried to change the status of an ADMIN or SUPER_ADMIN account. Without this an
     * ADMIN could suspend every SUPER_ADMIN and so take over System Configuration.
     */
    /**
     * {@code PENDING_VERIFICATION} belongs to an email sign-up whose code was never entered; staff
     * cannot put an account into it (they suspend or deactivate instead).
     */
    public static AdminUserException statusNotSettable() {
        return new AdminUserException(HttpStatus.BAD_REQUEST, "STATUS_NOT_SETTABLE",
                "An account can be set ACTIVE, SUSPENDED or DEACTIVATED.");
    }

    public static AdminUserException superAdminRequired() {
        return new AdminUserException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED",
                "Only a SUPER_ADMIN may change the status of an administrator account.");
    }

    /**
     * An internal caller asked to grant or revoke a role other than TENANT_ADMIN. The internal
     * role endpoints exist for provider-service's Tenant administrators only (Requirement
     * MT-2.2, MT-2.4); platform staff roles stay out of band, so a leaked service credential
     * cannot mint an ADMIN. Unknown and refused role names get the same answer.
     */
    public static AdminUserException roleNotManageable() {
        return new AdminUserException(HttpStatus.BAD_REQUEST, "ROLE_NOT_MANAGEABLE",
                "Only the TENANT_ADMIN role can be granted or revoked through this endpoint.");
    }

    /** The authenticated principal is not a user id this service issued. */
    public static AdminUserException invalidPrincipal() {
        return new AdminUserException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                "The authenticated principal is not a valid user id.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
