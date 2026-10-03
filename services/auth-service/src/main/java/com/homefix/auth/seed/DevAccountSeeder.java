package com.homefix.auth.seed;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Creates the local test accounts at startup so the stack is usable the moment it is up.
 *
 * <p>This exists because staff roles are deliberately not self-assignable: registration
 * refuses ADMIN and the rest, which is correct, but it also means a fresh database has no
 * account that can open the Admin Portal at all. Previously that gap was filled by a shell
 * script issuing SQL inserts after the fact. Doing it here instead keeps the role grant
 * inside the service that owns the role model, and makes it idempotent by construction.
 *
 * <p>Guarded three ways: the bean only exists when {@code homefix.auth.dev-seed.enabled} is
 * explicitly true, the password has no default so the deployment must supply one, and the
 * seeder refuses to run without it rather than inventing a value.
 *
 * <p>Idempotent. An existing account keeps its id — anything already referencing it stays
 * valid — and gains any missing role and the current credentials. Re-running after a
 * password change therefore resets the password, which is what a developer wants from a
 * seeder and exactly what you must never ship anywhere else.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.auth.dev-seed", name = "enabled", havingValue = "true")
public class DevAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

    /**
     * The accounts documented in {@code docs/LOCAL_ACCESS.md}. The mobile numbers match the
     * ones the OTP flow already used, so both sign-in paths reach the same account and a
     * booking made by {@code customer} is the booking {@code +919000000001} sees.
     */
    private static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount("customer", "+919000000001", Set.of(Role.CUSTOMER)),
            new SeedAccount("customer2", "+919000000002", Set.of(Role.CUSTOMER)),
            new SeedAccount("provider", "+919000000011", Set.of(Role.SERVICE_PROVIDER)),
            new SeedAccount("provider2", "+919000000012", Set.of(Role.SERVICE_PROVIDER)),
            new SeedAccount("admin", "+919000000021", Set.of(Role.ADMIN)),
            new SeedAccount("superadmin", "+919000000022", Set.of(Role.SUPER_ADMIN, Role.ADMIN)),
            new SeedAccount("finance", "+919000000023", Set.of(Role.FINANCE_ADMIN)),
            new SeedAccount("dispatcher", "+919000000024", Set.of(Role.DISPATCHER)),
            new SeedAccount("support", "+919000000025", Set.of(Role.SUPPORT_AGENT)),
            // The demo Tenant's administrator (Requirement MT-15.5). The Tenant membership itself
            // lives in provider-service; docker/seed-tenants.sql links this account by mobile.
            new SeedAccount("tenantadmin", "+919000000031", Set.of(Role.TENANT_ADMIN)));

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DevSeedProperties properties;

    public DevAccountSeeder(UserAccountRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            DevSeedProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            // Loud, and not fatal: a missing dev password should not stop the service from
            // serving the OTP flow, which needs no seeding.
            log.error("Dev account seeding is enabled but homefix.auth.dev-seed.password is not "
                    + "set. No accounts were seeded.");
            return;
        }

        // Hash once rather than per account: bcrypt at cost 12 is ~250ms, and ten of those
        // would be a visible delay on every start.
        String hash = passwordEncoder.encode(password);

        int created = 0;
        int updated = 0;
        for (SeedAccount seed : ACCOUNTS) {
            if (upsert(seed, hash)) {
                created++;
            } else {
                updated++;
            }
        }

        log.warn("DEV SEED: {} test account(s) created and {} refreshed, all sharing one "
                + "password. This must never run outside local development.", created, updated);
    }

    /**
     * Creates the account if absent, otherwise refreshes its roles and credentials.
     *
     * @return true when a new account was created
     */
    private boolean upsert(SeedAccount seed, String passwordHash) {
        UserAccount existing = userRepository.findByMobileNumber(seed.mobileNumber())
                .or(() -> userRepository.findByUsername(seed.username()))
                .orElse(null);

        if (existing == null) {
            // createVerified takes a single role; the rest are added below. Seeded accounts
            // are verified because the OTP step they would otherwise pass is not run here.
            Role first = seed.roles().iterator().next();
            UserAccount account = UserAccount.createVerified(seed.mobileNumber(), first);
            seed.roles().forEach(account::addRole);
            account.setCredentials(seed.username(), passwordHash);
            userRepository.save(account);
            return true;
        }

        seed.roles().forEach(existing::addRole);
        existing.setCredentials(seed.username(), passwordHash);
        userRepository.save(existing);
        return false;
    }
}
