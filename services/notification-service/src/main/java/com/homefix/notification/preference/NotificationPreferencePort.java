package com.homefix.notification.preference;

import java.util.Optional;
import java.util.UUID;

import com.homefix.notification.domain.NotificationPreferences;

/**
 * Port for looking up a user's channel preferences (Requirement 17.6).
 *
 * <p>Returns {@link Optional#empty()} when preference data is unavailable (lookup failed or no
 * record exists); the orchestrator then defaults to treating all channels as enabled. Adapters
 * must not throw for a missing user — they return empty so the default-on behaviour applies.
 */
public interface NotificationPreferencePort {

    Optional<NotificationPreferences> findByUserId(UUID userId);
}
