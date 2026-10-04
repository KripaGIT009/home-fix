package com.homefix.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;

/**
 * One row of the Admin Portal's User Management table: the portal's {@code AdminUser} type
 * (frontend/admin-portal/src/features/users/api.ts), field for field.
 *
 * <p>The Auth Service owns identity, not profiles, so {@code displayName} is the name given at
 * email sign-up or invitation, else the console username, else null; customer and provider
 * profile names live in their own services. {@code email} is null for an account without one, and
 * {@code mobileNumber} for a social-login account that never registered a phone. This is staff-only
 * PII and is never logged (Requirement 26.4).
 *
 * @param id           account id
 * @param displayName  display name, else console username, else null
 * @param mobileNumber E.164 mobile number, or null
 * @param email        lower-case email address, or null
 * @param roles        role names, sorted
 * @param status       ACTIVE, SUSPENDED, DEACTIVATED or PENDING_VERIFICATION
 * @param createdAt    when the account was created (ISO-8601)
 */
public record AdminUserResponse(
        UUID id,
        String displayName,
        String mobileNumber,
        String email,
        List<String> roles,
        AccountStatus status,
        Instant createdAt) {

    public static AdminUserResponse from(UserAccount account) {
        return new AdminUserResponse(
                account.getId(),
                account.getDisplayName() != null ? account.getDisplayName() : account.getUsername(),
                account.getMobileNumber(),
                account.getEmail(),
                account.getRoles().stream().map(Enum::name).sorted().toList(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
