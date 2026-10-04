package com.homefix.auth.api;

import java.util.UUID;

import com.homefix.auth.domain.UserAccount;

/**
 * Response for {@code GET /internal/users/{userId}/contact}: the delivery addresses the platform
 * holds for one user, so a service that addresses notifications by user id can resolve them
 * without every event carrying PII.
 *
 * <p>Every address is nullable. {@code mobileNumber} is null for a social-login account that never
 * registered a phone; {@code emailAddress} is the account's email once it has been verified, and
 * null otherwise (an unverified address may belong to someone else). Callers must treat a null address as "this channel cannot reach the user", not as an
 * error. These values are PII and must never be logged (Requirement 26.4).
 *
 * @param userId       the account id the addresses belong to
 * @param mobileNumber E.164 mobile number, or null
 * @param emailAddress email address, or null
 */
public record UserContactResponse(UUID userId, String mobileNumber, String emailAddress) {

    public static UserContactResponse from(UserAccount account) {
        return new UserContactResponse(account.getId(), account.getMobileNumber(),
                account.isEmailVerified() ? account.getEmail() : null);
    }
}
