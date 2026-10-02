package com.homefix.auth.admin;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.api.UserNotFoundException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenService;

/**
 * User Management for the Admin Portal (Requirement 19.2): list and search accounts, and suspend,
 * deactivate or reactivate one.
 *
 * <p>Changing a status is guarded beyond the RBAC rule that admits ADMIN and SUPER_ADMIN (see
 * {@code AuthRbacConfig}):
 * <ul>
 *   <li>nobody may change their own status;</li>
 *   <li>only a SUPER_ADMIN may change the status of an account holding ADMIN or SUPER_ADMIN, so
 *       an ADMIN cannot lock the platform's owners out.</li>
 * </ul>
 *
 * <p>What a status means is enforced where accounts authenticate, not here: every sign-in path and
 * refresh refuses a non-ACTIVE account, and introspection reports its access tokens inactive.
 * Disabling an account additionally revokes its refresh tokens, so a later reactivation does not
 * resurrect the sessions it had.
 *
 * <p>The Auth Service has no audit store (audit_log belongs to admin-service and no producer here
 * writes to it), so each change is logged at INFO with the actor and target ids. No phone number
 * or username is logged (Requirement 26.4).
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** The portal's user table takes a bare array with no paging, so the list is capped here. */
    static final int ADMIN_LIST_LIMIT = 200;

    private static final Set<Role> ADMINISTRATOR_ROLES = EnumSet.of(Role.ADMIN, Role.SUPER_ADMIN);

    private final UserAccountRepository userRepository;
    private final TokenService tokenService;

    public AdminUserService(UserAccountRepository userRepository, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * Accounts whose mobile number or console username contains {@code search}
     * (case-insensitive), or every account when it is blank; newest first, at most
     * {@link #ADMIN_LIST_LIMIT}.
     */
    @Transactional(readOnly = true)
    public List<UserAccount> search(String search) {
        PageRequest page = PageRequest.of(0, ADMIN_LIST_LIMIT);
        String term = search == null ? "" : search.trim();
        if (term.isEmpty()) {
            return userRepository.findByOrderByCreatedAtDesc(page);
        }
        String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
        return userRepository.searchForAdmin(pattern, page);
    }

    /**
     * Sets an account's status and, when the account can no longer authenticate, revokes all of
     * its refresh tokens.
     *
     * <p>Deliberately not one transaction: the status is committed by the repository's own
     * transaction <em>before</em> the refresh tokens are revoked. A sign-in that starts after the
     * revocation therefore always sees the new status, and if the revocation fails the account is
     * still locked out (rotation re-checks the status) and the call can simply be repeated.
     *
     * @return the updated account
     * @throws UserNotFoundException 404 if no account has that id
     * @throws AdminUserException    403 for a self-change, or an ADMIN acting on an administrator
     */
    public UserAccount changeStatus(UUID userId, AccountStatus newStatus, StaffActor actor) {
        UserAccount account = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (account.getId().equals(actor.userId())) {
            throw AdminUserException.selfStatusChange();
        }
        boolean targetIsAdministrator = account.getRoles().stream()
                .anyMatch(ADMINISTRATOR_ROLES::contains);
        if (targetIsAdministrator && !actor.isSuperAdmin()) {
            throw AdminUserException.superAdminRequired();
        }

        AccountStatus previous = account.getStatus();
        account.changeStatus(newStatus);
        UserAccount saved = userRepository.save(account);

        if (!newStatus.canAuthenticate()) {
            tokenService.revokeAllRefreshTokens(saved.getId().toString());
        }
        log.info("Account status changed by staff user {}: account {} {} -> {}",
                actor.userId(), saved.getId(), previous, newStatus);
        return saved;
    }

    /** Escapes the {@code LIKE} wildcards so a search term matches literally. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
