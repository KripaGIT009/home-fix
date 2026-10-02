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
 * <p>The Auth Service owns identity, not profiles, so two fields are partial. {@code displayName}
 * is the console username where the account has one (staff accounts) and null otherwise; the
 * customer and provider names live in their own services and are not fetched from here.
 * {@code email} is always null, because no email is stored yet (as in
 * {@link UserContactResponse}). {@code mobileNumber} is null for a social-login account that never
 * registered a phone. This is staff-only PII and is never logged (Requirement 26.4).
 *
 * @param id           account id
 * @param displayName  console username, or null
 * @param mobileNumber E.164 mobile number, or null
 * @param email        always null today
 * @param roles        role names, sorted
 * @param status       ACTIVE, SUSPENDED or DEACTIVATED
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
                account.getUsername(),
                account.getMobileNumber(),
                null,
                account.getRoles().stream().map(Enum::name).sorted().toList(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
