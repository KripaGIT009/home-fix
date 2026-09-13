package com.homefix.auth.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link UserAccount} records.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByMobileNumber(String mobileNumber);

    /**
     * Looks an account up by its console username for password sign-in. Usernames are
     * stored and queried in lower case, so the caller normalises before calling.
     */
    Optional<UserAccount> findByUsername(String username);

    boolean existsByMobileNumber(String mobileNumber);
}
