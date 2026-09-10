package com.homefix.notification.preference;

import java.util.Optional;
import java.util.UUID;

import com.homefix.notification.domain.NotificationPreferences;
import org.springframework.stereotype.Component;

/**
 * Default {@link NotificationPreferencePort} used when no real preference source is wired.
 *
 * <p>It always reports preference data as unavailable ({@link Optional#empty()}), which the
 * orchestrator interprets as "all channels enabled" (Requirement 17.6). A production deployment
 * supplies an adapter that reads the user's stored preferences (e.g. from the Customer Service).
 * Activated only when no other {@link NotificationPreferencePort} bean is present.
 */
@Component
public class DefaultAllEnabledPreferenceAdapter implements NotificationPreferencePort {

    @Override
    public Optional<NotificationPreferences> findByUserId(UUID userId) {
        return Optional.empty();
    }
}
