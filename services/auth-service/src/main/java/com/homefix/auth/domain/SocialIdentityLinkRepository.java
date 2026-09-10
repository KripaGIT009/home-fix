package com.homefix.auth.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homefix.auth.social.SocialProvider;

/**
 * Persistence for {@link SocialIdentityLink} records.
 */
public interface SocialIdentityLinkRepository extends JpaRepository<SocialIdentityLink, UUID> {

    Optional<SocialIdentityLink> findByProviderAndProviderSubject(SocialProvider provider, String providerSubject);
}
