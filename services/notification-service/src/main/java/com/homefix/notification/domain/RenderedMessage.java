package com.homefix.notification.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The outcome of rendering a {@link NotificationEvent} into deliverable content: the target
 * channels and the title/body text.
 *
 * <p>Content is derived only from non-PII template attributes, so a rendered message is safe to
 * pass to channel adapters without leaking personal data (Requirement 26.4).
 *
 * <p>An admin can edit a template's text per channel (Requirement 19.2), so a message may carry
 * different text for, say, SMS and email. Channel adapters must read {@link #titleFor} and
 * {@link #bodyFor}; {@link #title()} and {@link #body()} are the template's built-in text, used
 * for any channel without its own content.
 *
 * @param channels       the channels this event should be delivered on (before preference filtering)
 * @param title          short heading (used by push / in-app / email subject)
 * @param body           the message body
 * @param channelContent rendered text per channel, overriding {@code title}/{@code body}
 */
public record RenderedMessage(Set<NotificationChannel> channels, String title, String body,
                              Map<NotificationChannel, ChannelContent> channelContent) {

    /** The rendered heading and body for one channel. */
    public record ChannelContent(String title, String body) {

        public ChannelContent {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(body, "body");
        }
    }

    public RenderedMessage {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        channels = channels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(channels);
        channelContent = channelContent == null || channelContent.isEmpty()
                ? Map.of()
                : Map.copyOf(new EnumMap<>(channelContent));
    }

    /** A message with the same text on every channel. */
    public RenderedMessage(Set<NotificationChannel> channels, String title, String body) {
        this(channels, title, body, Map.of());
    }

    @Override
    public Set<NotificationChannel> channels() {
        return channels.isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(channels);
    }

    /** The heading to send on a channel. */
    public String titleFor(NotificationChannel channel) {
        ChannelContent content = channelContent.get(channel);
        return content == null ? title : content.title();
    }

    /** The body to send on a channel. */
    public String bodyFor(NotificationChannel channel) {
        ChannelContent content = channelContent.get(channel);
        return content == null ? body : content.body();
    }
}
