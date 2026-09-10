package com.homefix.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * A user's per-channel notification preferences (Requirement 17.6).
 *
 * <p>A channel is delivered to only if the user has it enabled. When preference data is
 * unavailable at dispatch time, the service defaults to treating <em>all</em> channels as enabled
 * — use {@link #allEnabled()} for that case.
 *
 * <p>This is an immutable value object with no framework dependencies, so it is trivially unit-
 * and property-testable.
 */
public final class NotificationPreferences {

    private final Set<NotificationChannel> enabledChannels;

    private NotificationPreferences(Set<NotificationChannel> enabledChannels) {
        // Defensive copy into an EnumSet so callers cannot mutate internal state.
        this.enabledChannels = enabledChannels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(enabledChannels);
    }

    /** Preferences that enable every channel — the default when preference data is unavailable. */
    public static NotificationPreferences allEnabled() {
        return new NotificationPreferences(EnumSet.allOf(NotificationChannel.class));
    }

    /** Preferences enabling exactly the supplied channels. */
    public static NotificationPreferences of(Set<NotificationChannel> enabledChannels) {
        return new NotificationPreferences(enabledChannels);
    }

    /** Preferences enabling exactly the supplied channels. */
    public static NotificationPreferences of(NotificationChannel... enabledChannels) {
        Set<NotificationChannel> set = EnumSet.noneOf(NotificationChannel.class);
        for (NotificationChannel channel : enabledChannels) {
            set.add(channel);
        }
        return new NotificationPreferences(set);
    }

    /** Preferences that disable every channel. */
    public static NotificationPreferences none() {
        return new NotificationPreferences(EnumSet.noneOf(NotificationChannel.class));
    }

    /** Whether the given channel is enabled for the user. */
    public boolean isEnabled(NotificationChannel channel) {
        return enabledChannels.contains(channel);
    }

    /** An immutable snapshot of the enabled channels. */
    public Set<NotificationChannel> enabledChannels() {
        return EnumSet.copyOf(enabledChannels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : enabledChannels);
    }
}
