package com.homefix.auth.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Invitation and email-account queries on H2 with the schema Hibernate generates: an invitation is
 * accepted at most once, the Invitations tab lists only links that still work, and the email and
 * stale-sign-up lookups used by sign-in and the sweep.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-invitations;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StaffInvitationRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private StaffInvitationRepository invitations;
    @Autowired
    private UserAccountRepository users;

    private StaffInvitation persist(String email, Instant expiresAt) {
        StaffInvitation invitation = new StaffInvitation(email, Role.DISPATCHER, UUID.randomUUID().toString(),
                UUID.randomUUID(), Instant.now(), expiresAt);
        return entityManager.persistAndFlush(invitation);
    }

    @Test
    void anInvitation_isAcceptedAtMostOnce() {
        StaffInvitation invitation = persist("a@example.com", Instant.now().plus(Duration.ofDays(7)));

        assertThat(invitations.markAccepted(invitation.getId(), UUID.randomUUID(), Instant.now())).isEqualTo(1);
        assertThat(invitations.markAccepted(invitation.getId(), UUID.randomUUID(), Instant.now())).isZero();
    }

    @Test
    void theInvitationsTab_listsOnlyLinksThatStillWork() {
        StaffInvitation usable = persist("a@example.com", Instant.now().plus(Duration.ofDays(7)));
        persist("b@example.com", Instant.now().minus(Duration.ofMinutes(1)));
        StaffInvitation revoked = persist("c@example.com", Instant.now().plus(Duration.ofDays(7)));
        revoked.revoke(Instant.now());
        entityManager.persistAndFlush(revoked);

        assertThat(invitations.findUsable(Instant.now())).extracting(StaffInvitation::getId)
                .containsExactly(usable.getId());
        assertThat(invitations.findOpenByEmail("b@example.com")).hasSize(1);
        assertThat(invitations.findOpenByEmail("c@example.com")).isEmpty();
    }

    @Test
    void emailAccounts_areFoundByAddress_andStaleSignupsByAge() throws Exception {
        UserAccount fresh = UserAccount.createPendingEmailSignup("A", "fresh@example.com", "+919811100001",
                Role.CUSTOMER, "h");
        UserAccount stale = UserAccount.createPendingEmailSignup("B", "stale@example.com", "+919811100002",
                Role.CUSTOMER, "h");
        Field createdAt = UserAccount.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(stale, Instant.now().minus(Duration.ofHours(25)));
        entityManager.persist(fresh);
        entityManager.persist(stale);
        entityManager.flush();
        entityManager.clear();

        assertThat(users.findByEmail("fresh@example.com")).isPresent();
        assertThat(users.findByStatusAndCreatedAtBefore(AccountStatus.PENDING_VERIFICATION,
                Instant.now().minus(Duration.ofHours(24))))
                .extracting(UserAccount::getEmail).containsExactly("stale@example.com");
    }
}
