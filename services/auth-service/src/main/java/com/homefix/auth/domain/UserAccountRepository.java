package com.homefix.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The account's status alone, for token introspection. The API Gateway introspects on every
     * uncached request, so this reads one column by primary key rather than loading the entity
     * and its role collection.
     */
    @Query("select u.status from UserAccount u where u.id = :id")
    Optional<AccountStatus> findStatusById(@Param("id") UUID id);

    /** Every account, newest first, bounded by {@code page}: the Admin Portal's unfiltered list. */
    List<UserAccount> findByOrderByCreatedAtDesc(Pageable page);

    /**
     * Accounts whose mobile number or console username contains {@code pattern} (a lower-case
     * {@code LIKE} pattern with {@code \} escaping literal wildcards), newest first, bounded by
     * {@code page}. Either column may be null, and a null column simply does not match, which is
     * why the unfiltered list is a separate query: a social-login account with neither would
     * otherwise vanish from it.
     */
    @Query("""
            select u from UserAccount u
            where lower(u.mobileNumber) like :pattern escape '\\'
               or lower(u.username) like :pattern escape '\\'
            order by u.createdAt desc
            """)
    List<UserAccount> searchForAdmin(@Param("pattern") String pattern, Pageable page);
}
