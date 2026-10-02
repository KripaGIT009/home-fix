package com.homefix.auth.api;

import java.util.UUID;

/**
 * Raised by an internal lookup or an Admin Portal action for a user id that has no account. Surfaced as 404
 * {@code USER_NOT_FOUND} through the shared error envelope; the id is not echoed in the message
 * so the response carries nothing beyond the status.
 */
public class UserNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "USER_NOT_FOUND";

    private final UUID userId;

    public UserNotFoundException(UUID userId) {
        super("No account exists for the requested user");
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
