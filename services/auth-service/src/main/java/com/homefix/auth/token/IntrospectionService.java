package com.homefix.auth.token;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Token introspection for the API Gateway: a token is active only if it verifies <em>and</em> the
 * account it was issued to may still authenticate.
 *
 * <p>Access tokens are stateless JWTs, so on their own they stay valid until {@code exp} however
 * the account changes. Checking the account's status here is what makes a suspension
 * (Requirement 19.2) end existing sessions: the gateway introspects every request it has not
 * cached, so an access token held by a suspended or deactivated account stops being admitted
 * within the gateway's introspection-cache TTL rather than at the end of the access-token TTL.
 *
 * <p>An account that no longer exists is reported inactive too. The check costs one primary-key
 * read of a single column, and only for tokens whose signature and expiry are already valid. A
 * database failure propagates as a 5xx, which the gateway treats as inactive (fail closed).
 */
@Service
public class IntrospectionService {

    private static final Map<String, Object> INACTIVE = Map.of("active", false);

    private final TokenService tokenService;
    private final UserAccountRepository userRepository;

    public IntrospectionService(TokenService tokenService, UserAccountRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    /**
     * @return {@code {active:true, sub, roles, exp, iss}} for a valid token held by an ACTIVE
     *         account; {@code {active:false}} otherwise
     */
    public Map<String, Object> introspect(String accessToken) {
        Map<String, Object> claims = tokenService.introspect(accessToken);
        if (!Boolean.TRUE.equals(claims.get("active"))) {
            return claims;
        }
        UUID userId;
        try {
            userId = UUID.fromString(String.valueOf(claims.get("sub")));
        } catch (IllegalArgumentException ex) {
            // Every token this service issues has a UUID subject; anything else is not ours.
            return INACTIVE;
        }
        boolean accountActive = userRepository.findStatusById(userId)
                .map(AccountStatus::canAuthenticate)
                .orElse(false);
        return accountActive ? claims : INACTIVE;
    }
}
