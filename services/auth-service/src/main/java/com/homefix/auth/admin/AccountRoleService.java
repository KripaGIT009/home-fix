package com.homefix.auth.admin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.homefix.auth.api.UserNotFoundException;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenService;

/**
 * Account lookup and role changes for other platform services, behind the internal surface
 * ({@code InternalUserRoleController}). Today the only caller is provider-service, which owns
 * Tenants: a Platform_Admin adds a Tenant administrator by mobile number, provider-service looks
 * the account up here and grants {@link Role#TENANT_ADMIN}; removing the administrator revokes it
 * (Requirement MT-2.2, MT-2.4).
 *
 * <p>Only {@link #MANAGEABLE_ROLES} can be changed here. Platform staff roles stay granted out of
 * band (seeder, or an existing administrator), so the shared service credential is never enough
 * to make someone an ADMIN.
 *
 * <p>Both changes are idempotent, because provider-service retries and compensates (grant before
 * recording the membership, revoke if recording fails). Tokens pick the change up without any
 * help from here: sign-in and refresh both read the roles from the account (Requirement MT-2.5).
 * A revocation additionally ends the account's sessions; see {@link #revoke}.
 *
 * <p>As in {@link AdminUserService}, the Auth Service has no audit store, so each change is logged
 * at INFO with the target id and role. The acting Platform_Admin is known only to provider-service,
 * which logs it (Requirement MT-1.3). No phone number is logged (Requirement 26.4).
 */
@Service
public class AccountRoleService {

    private static final Logger log = LoggerFactory.getLogger(AccountRoleService.class);

    /** The roles another service may grant or revoke. */
    static final Set<Role> MANAGEABLE_ROLES = Set.of(Role.TENANT_ADMIN);

    private final UserAccountRepository userRepository;
    private final TokenService tokenService;

    public AccountRoleService(UserAccountRepository userRepository, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * The account registered with {@code mobileNumber}, matched exactly as stored (E.164).
     *
     * <p>An unencoded {@code +} in a query string arrives as a space, so a value that starts with
     * a space is read as starting with {@code +}: a caller that forgot to encode the number gets
     * the account it meant rather than a misleading 404.
     */
    public Optional<UserAccount> findByMobile(String mobileNumber) {
        if (mobileNumber == null || mobileNumber.isBlank()) {
            return Optional.empty();
        }
        String normalised = mobileNumber.startsWith(" ")
                ? "+" + mobileNumber.strip()
                : mobileNumber.strip();
        return userRepository.findByMobileNumber(normalised);
    }

    /**
     * Grants a manageable role. Granting a role the account already holds changes nothing.
     *
     * <p>A disabled account may be granted the role: it still cannot sign in, and the decision to
     * make a suspended person a Tenant administrator belongs to the caller, which receives the
     * account status from the lookup.
     *
     * @return the account after the change
     * @throws AdminUserException    400 {@code ROLE_NOT_MANAGEABLE} for any other role name
     * @throws UserNotFoundException 404 if no account has that id
     */
    public UserAccount grant(UUID userId, String roleName) {
        Role role = manageableRole(roleName);
        UserAccount account = load(userId);
        if (!account.addRole(role)) {
            return account;
        }
        UserAccount saved;
        try {
            saved = userRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent grant inserted the same (user, role) row first; the outcome the caller
            // asked for holds, so answer as an idempotent repeat would.
            UserAccount current = load(userId);
            if (current.getRoles().contains(role)) {
                return current;
            }
            throw ex;
        }
        log.info("Role {} granted to account {} by internal caller", role, saved.getId());
        return saved;
    }

    /**
     * Revokes a manageable role and ends every session of the account.
     *
     * <p>The role is committed first and the refresh-token families are revoked after, as
     * {@link AdminUserService#changeStatus} does: a refresh that starts after the revocation
     * reads the account without the role, and if revoking the sessions fails the call can simply
     * be repeated. The sessions are revoked even when the account no longer held the role, so a
     * repeat after a partial failure still ends them. Ending them is stricter than Requirement
     * MT-2.4 asks (the role would drop out at the next refresh anyway, since refresh re-reads
     * roles) but means a removed administrator signs in again rather than carrying on with a
     * session started under the old role. Access tokens already issued are stateless and keep
     * their roles until they expire (the access TTL, 15 minutes by default).
     *
     * @return the account after the change
     * @throws AdminUserException    400 {@code ROLE_NOT_MANAGEABLE} for any other role name
     * @throws UserNotFoundException 404 if no account has that id
     */
    public UserAccount revoke(UUID userId, String roleName) {
        Role role = manageableRole(roleName);
        UserAccount account = load(userId);
        UserAccount current = account;
        boolean held = account.removeRole(role);
        if (held) {
            current = userRepository.save(account);
        }
        tokenService.revokeAllRefreshTokens(current.getId().toString());
        if (held) {
            log.info("Role {} revoked from account {} by internal caller; sessions ended", role, current.getId());
        }
        return current;
    }

    private UserAccount load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    /** Resolves the path's role name, refusing unknown and non-manageable names alike. */
    private static Role manageableRole(String roleName) {
        for (Role role : MANAGEABLE_ROLES) {
            if (role.name().equals(roleName)) {
                return role;
            }
        }
        throw AdminUserException.roleNotManageable();
    }
}
