package com.homefix.notification.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The outcome of rendering a {@link NotificationEvent} into deliverable content: the target
 * channels and the title/body text.
 *
 * <p>Content is derived only from non-PII template attributes, so a rendered message is safe to
 * pass to channel adapters without leaking personal data (Requirement 26.4).
 *
 * @param channels the channels this event should be delivered on (before preference filtering)
 * @param title    short heading (used by push / in-app / email subject)
 * @param body     the message body
 */
public record RenderedMessage(Set<NotificationChannel> channels, String title, String body) {

    public RenderedMessage {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        channels = channels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(channels);
    }

    public Set<NotificationChannel> channels() {
        return channels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(channels);
    }
}
