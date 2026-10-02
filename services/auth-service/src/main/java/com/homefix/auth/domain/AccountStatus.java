package com.homefix.auth.domain;

/**
 * Lifecycle status of a {@link UserAccount}, set by an administrator from the Admin Portal's
 * User Management screen (Requirement 19.2). The names are the portal's {@code UserStatus}
 * values, so they travel over the wire unchanged.
 *
 * <p>Only {@link #ACTIVE} may authenticate. {@link #SUSPENDED} (a temporary hold, typically
 * pending an investigation) and {@link #DEACTIVATED} (the account is closed) are treated alike by
 * the Auth Service: no sign-in on any path, no refresh, and introspection reports the account's
 * outstanding access tokens inactive so the API Gateway stops admitting them. The difference is
 * an operational one for the staff reading the record, and either can be reversed by setting the
 * account back to {@link #ACTIVE}.
 */
public enum AccountStatus {

    ACTIVE,

    SUSPENDED,

    DEACTIVATED;

    /** Whether an account in this status may sign in, refresh, or present a token. */
    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
