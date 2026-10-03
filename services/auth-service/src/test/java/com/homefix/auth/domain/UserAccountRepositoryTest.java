package com.homefix.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

/**
 * Runs the Admin Portal queries on {@link UserAccountRepository} against an in-memory H2 database
 * with the schema Hibernate generates from the entity (the Flyway migrations are PostgreSQL DDL and
 * are exercised against the real database instead). Covers the case-insensitive substring search
 * over mobile number and username, literal wildcards, newest-first ordering, the page bound, an
 * unfiltered list that keeps accounts with neither column, and the status-only lookup.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-repo;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserAccountRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserAccountRepository repository;

    private UserAccount oldest;
    private UserAccount staff;
    private UserAccount social;
    private UserAccount newest;

    @BeforeEach
    void setUp() throws Exception {
        oldest = persist(UserAccount.createVerified("+919876500001", Role.CUSTOMER), 0);
        staff = UserAccount.createVerified("+919000000021", Role.ADMIN);
        staff.setCredentials("ops_lead", "$2a$12$hash");
        staff = persist(staff, 1);
        social = persist(UserAccount.createSocial(Role.CUSTOMER), 2);
        newest = UserAccount.createVerified("+919876500002", Role.SERVICE_PROVIDER);
        newest.changeStatus(AccountStatus.SUSPENDED);
        newest = persist(newest, 3);
        entityManager.flush();
        entityManager.clear();
    }

    /** Persists with a controlled creation time, so ordering does not depend on the clock. */
    private UserAccount persist(UserAccount account, int minutesAfterT0) throws Exception {
        Field createdAt = UserAccount.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(account, T0.plusSeconds(60L * minutesAfterT0));
        return entityManager.persist(account);
    }

    private static List<UUID> ids(List<UserAccount> accounts) {
        return accounts.stream().map(UserAccount::getId).toList();
    }

    @Test
    void unfilteredList_isNewestFirstAndKeepsAnAccountWithNeitherColumn() {
        List<UserAccount> all = repository.findByOrderByCreatedAtDesc(PageRequest.of(0, 200));

        assertThat(ids(all)).containsExactly(newest.getId(), social.getId(), staff.getId(), oldest.getId());
    }

    @Test
    void unfilteredList_isBoundedByThePage() {
        assertThat(ids(repository.findByOrderByCreatedAtDesc(PageRequest.of(0, 2))))
                .containsExactly(newest.getId(), social.getId());
    }

    @Test
    void search_matchesAMobileNumberSubstringNewestFirst() {
        assertThat(ids(repository.searchForAdmin("%98765%", PageRequest.of(0, 200))))
                .containsExactly(newest.getId(), oldest.getId());
    }

    @Test
    void search_matchesAUsernameCaseInsensitively() {
        // The service lower-cases the term; the query lower-cases the column.
        assertThat(ids(repository.searchForAdmin("%ops%", PageRequest.of(0, 200))))
                .containsExactly(staff.getId());
    }

    @Test
    void search_escapedWildcardMatchesLiterally() {
        assertThat(ids(repository.searchForAdmin("%ops\\_lead%", PageRequest.of(0, 200))))
                .containsExactly(staff.getId());
        assertThat(repository.searchForAdmin("%ops\\%lead%", PageRequest.of(0, 200))).isEmpty();
    }

    @Test
    void statusAndRolesRoundTrip() {
        UserAccount reloaded = repository.findById(newest.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(reloaded.getRoles()).containsExactly(Role.SERVICE_PROVIDER);
        assertThat(repository.findById(oldest.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void tenantAdminRole_isGrantedAndRevokedThroughTheRoleTable() {
        // Requirement MT-2.1, MT-2.4: the role is stored like any other and can be removed again.
        UserAccount account = repository.findByMobileNumber("+919876500001").orElseThrow();
        account.addRole(Role.TENANT_ADMIN);
        repository.saveAndFlush(account);
        entityManager.clear();

        UserAccount granted = repository.findById(oldest.getId()).orElseThrow();
        assertThat(granted.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.TENANT_ADMIN);

        granted.removeRole(Role.TENANT_ADMIN);
        repository.saveAndFlush(granted);
        entityManager.clear();

        assertThat(repository.findById(oldest.getId()).orElseThrow().getRoles())
                .containsExactly(Role.CUSTOMER);
    }

    @Test
    void findStatusById_readsJustTheStatus() {
        assertThat(repository.findStatusById(newest.getId())).contains(AccountStatus.SUSPENDED);
        assertThat(repository.findStatusById(staff.getId())).contains(AccountStatus.ACTIVE);
        assertThat(repository.findStatusById(UUID.randomUUID())).isEmpty();
    }
}
