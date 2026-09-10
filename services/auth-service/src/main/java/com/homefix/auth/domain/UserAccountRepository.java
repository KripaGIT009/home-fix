package com.homefix.auth.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link UserAccount} records.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByMobileNumber(String mobileNumber);

    boolean existsByMobileNumber(String mobileNumber);
}
