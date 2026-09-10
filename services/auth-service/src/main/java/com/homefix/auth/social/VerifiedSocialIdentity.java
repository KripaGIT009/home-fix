package com.homefix.auth.social;

/**
 * The verified result of validating a provider identity token (Requirement 1.5).
 *
 * @param provider        the issuing social provider
 * @param providerSubject the provider's stable, unique user identifier (e.g. Google {@code sub},
 *                        Apple {@code sub}); used to link the account across logins
 * @param email           the verified email address if the provider asserted one, else {@code null}
 */
public record VerifiedSocialIdentity(SocialProvider provider, String providerSubject, String email) {
}
