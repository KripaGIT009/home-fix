package com.homefix.auth.token;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Orchestrates refresh-token rotation and logout revocation (Requirement 1.9, 1.10, 1.12).
 *
 * <p>Rotation consumes the presented refresh token (detecting replay and invalidating the
 * family), resolves the account's <em>current</em> roles so the new access token reflects any
 * role changes (Requirement 1.14), and issues the rotated token pair. The token family stores
 * only the subject, never roles, so a TENANT_ADMIN granted or revoked by provider-service appears
 * in or disappears from the very next refreshed token (Requirement MT-2.5).
 *
 * <p>The account is re-read on every rotation for its status as well as its roles: an account an
 * administrator has suspended or deactivated (Requirement 19.2) cannot refresh, and the presented
 * token's whole family is revoked so the session does not come back if the account is later
 * reactivated.
 */
@Service
public class RefreshService {

    private final TokenService tokenService;
    private final UserAccountRepository userRepository;

    public RefreshService(TokenService tokenService, UserAccountRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    /**
     * Rotates the presented refresh token, returning a fresh access + refresh token pair.
     *
     * @throws TokenException 401 if the token is invalid/expired/revoked, or if replay is
     *                        detected (family invalidated)
     * @throws AccountDisabledException 403 if the account is no longer ACTIVE (family revoked)
     */
    public RefreshResult refresh(String presentedRefreshToken) {
        RefreshTokenRecord consumed = tokenService.consumeRefreshTokenForRotation(presentedRefreshToken);

        UserAccount account = userRepository.findById(parseSubject(consumed.subject()))
                .orElseThrow(TokenException::invalidRefreshToken);
        if (!account.getStatus().canAuthenticate()) {
            tokenService.revokeTokenFamily(consumed.familyId());
            throw new AccountDisabledException(account.getStatus());
        }

        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueRotatedTokens(consumed, roles);
        return new RefreshResult(consumed.subject(), roles, tokens);
    }

    /**
     * Revokes the refresh token's whole family on logout (Requirement 1.12), so no token rotated
     * from the same login survives it. Idempotent.
     */
    public void logout(String refreshToken) {
        tokenService.revokeRefreshToken(refreshToken);
    }

    private UUID parseSubject(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw TokenException.invalidRefreshToken();
        }
    }

    /**
     * Outcome of a successful rotation.
     */
    public record RefreshResult(String userId, List<String> roles, TokenPair tokens) {
    }
}
