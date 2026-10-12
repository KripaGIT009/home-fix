package com.homefix.auth.emailauth;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;

/**
 * Removes email sign-ups left unverified past their lifetime, 24 hours by default (email-auth
 * Requirement 1.7). Sign-up already ignores such an account, so this only keeps the table tidy and
 * frees the email and mobile number for good. Hourly; disable with
 * {@code homefix.auth.email-auth.sweep-enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.auth.email-auth", name = "sweep-enabled", havingValue = "true",
        matchIfMissing = true)
public class PendingSignupSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingSignupSweeper.class);

    private final UserAccountRepository userRepository;
    private final EmailAuthProperties properties;

    public PendingSignupSweeper(UserAccountRepository userRepository, EmailAuthProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    public void sweep() {
        sweep(Instant.now());
    }

    /** @return how many unverified sign-ups were removed */
    public int sweep(Instant now) {
        List<UserAccount> stale = userRepository.findByStatusAndCreatedAtBefore(
                AccountStatus.PENDING_VERIFICATION, now.minus(properties.getPendingSignupTtl()));
        if (!stale.isEmpty()) {
            userRepository.deleteAll(stale);
            log.info("Removed {} email sign-up(s) never verified within {}", stale.size(),
                    properties.getPendingSignupTtl());
        }
        return stale.size();
    }
}
