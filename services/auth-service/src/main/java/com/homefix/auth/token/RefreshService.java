package com.homefix.auth.token;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Orchestrates refresh-token rotation and logout revocation (Requirement 1.9, 1.10, 1.12).
 *
 * <p>Rotation consumes the presented refresh token (detecting replay and invalidating the
 * family), resolves the account's <em>current</em> roles so the new access token reflects any
 * role changes (Requirement 1.14), and issues the rotated token pair.
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
     */
    public RefreshResult refresh(String presentedRefreshToken) {
        RefreshTokenRecord consumed = tokenService.consumeRefreshTokenForRotation(presentedRefreshToken);

        List<String> roles = resolveRoles(consumed.subject());
        TokenPair tokens = tokenService.issueRotatedTokens(consumed, roles);
        return new RefreshResult(consumed.subject(), roles, tokens);
    }

    /**
     * Revokes a refresh token on logout (Requirement 1.12). Idempotent.
     */
    public void logout(String refreshToken) {
        tokenService.revokeRefreshToken(refreshToken);
    }

    private List<String> resolveRoles(String subject) {
        return userRepository.findById(UUID.fromString(subject))
                .map(UserAccount::getRoles)
                .map(rs -> rs.stream().map(Enum::name).sorted().toList())
                .orElseThrow(TokenException::invalidRefreshToken);
    }

    /**
     * Outcome of a successful rotation.
     */
    public record RefreshResult(String userId, List<String> roles, TokenPair tokens) {
    }
}
