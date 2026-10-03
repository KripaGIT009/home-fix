package com.homefix.auth.api;

import java.util.List;
import java.util.UUID;

import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;

/**
 * Response of the internal account lookup and role endpoints ({@code /internal/users/by-mobile},
 * {@code /internal/users/{userId}/roles/{role}}): who the account is, what it may do, and whether
 * it may sign in, so provider-service can decide whether to make it a Tenant administrator or a
 * Tenant member (Requirement MT-2.2, MT-3.2). Carries no contact details.
 *
 * @param userId account id
 * @param roles  role names, sorted
 * @param status ACTIVE, SUSPENDED or DEACTIVATED
 */
public record InternalUserResponse(UUID userId, List<String> roles, AccountStatus status) {

    public static InternalUserResponse from(UserAccount account) {
        return new InternalUserResponse(
                account.getId(),
                account.getRoles().stream().map(Enum::name).sorted().toList(),
                account.getStatus());
    }
}
