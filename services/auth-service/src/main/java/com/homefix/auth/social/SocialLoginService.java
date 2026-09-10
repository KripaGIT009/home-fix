package com.homefix.auth.social;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.SocialIdentityLink;
import com.homefix.auth.domain.SocialIdentityLinkRepository;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Orchestrates social login (Requirement 1.5).
 *
 * <ol>
 *   <li>Validates the provider identity token via the provider-specific
 *       {@link SocialIdentityVerifier} (resolved by {@link SocialIdentityVerifierResolver}).</li>
 *   <li>Creates the account on first login, or retrieves the linked account on subsequent
 *       logins, keyed by {@code (provider, provider subject)}.</li>
 *   <li>Issues a JWT access token + refresh token carrying the account's roles
 *       (Requirement 1.14).</li>
 * </ol>
 *
 * <p>An invalid or expired identity token propagates as a {@link SocialIdentityException}
 * which the REST layer maps to a 401 with an error code (Requirement 1.5).
 */
@Service
public class SocialLoginService {

    private final SocialIdentityVerifierResolver verifierResolver;
    private final SocialIdentityLinkRepository linkRepository;
    private final UserAccountRepository userRepository;
    private final TokenService tokenService;

    public SocialLoginService(SocialIdentityVerifierResolver verifierResolver,
                              SocialIdentityLinkRepository linkRepository,
                              UserAccountRepository userRepository,
                              TokenService tokenService) {
        this.verifierResolver = verifierResolver;
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * Authenticates a user via a social provider, creating the account on first login and
     * retrieving it thereafter.
     *
     * @param provider      the social provider (Google, Apple)
     * @param identityToken the raw provider identity token
     * @return issued tokens plus the account id and roles
     */
    @Transactional
    public SocialLoginResult login(SocialProvider provider, String identityToken) {
        VerifiedSocialIdentity identity = verifierResolver.resolve(provider).verify(identityToken);

        UserAccount account = linkRepository
                .findByProviderAndProviderSubject(identity.provider(), identity.providerSubject())
                .flatMap(link -> userRepository.findById(link.getUserId()))
                .orElseGet(() -> createLinkedAccount(identity));

        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueTokens(account.getId().toString(), roles);
        return new SocialLoginResult(account.getId().toString(), roles, tokens);
    }

    private UserAccount createLinkedAccount(VerifiedSocialIdentity identity) {
        UserAccount account = userRepository.save(UserAccount.createSocial(Role.CUSTOMER));
        linkRepository.save(SocialIdentityLink.create(
                identity.provider(), identity.providerSubject(), account.getId()));
        return account;
    }

    /**
     * Outcome of a successful social login.
     */
    public record SocialLoginResult(String userId, List<String> roles, TokenPair tokens) {
    }
}
