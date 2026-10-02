package com.homefix.notification.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One built-in message template: the text a single audience receives for a single event (or one
 * variant of it, such as a cancellation that is really "no professional found"), and the channels
 * it goes out on.
 *
 * <p>An admin edits the text per channel (Requirement 19.2), so each definition expands to one
 * editable template per channel, identified by {@link #idFor(NotificationChannel)}. The channel
 * set itself is not editable: it encodes requirements such as push + SMS on acceptance
 * (Requirement 8.10), not copy.
 *
 * @param key       stable identifier, {@code EVENT.AUDIENCE[.VARIANT]}, e.g.
 *                  {@code BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED}
 * @param eventType the event whose attributes fill the placeholders
 * @param name      human-readable name shown to admins
 * @param channels  the candidate delivery channels (before preference filtering)
 * @param title     built-in heading (push / in-app title, email subject) with {{placeholders}}
 * @param body      built-in body with {{placeholders}}
 */
public record TemplateDefinition(String key,
                                 NotificationEventType eventType,
                                 String name,
                                 Set<NotificationChannel> channels,
                                 String title,
                                 String body) {

    public TemplateDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        channels = EnumSet.copyOf(channels);
    }

    @Override
    public Set<NotificationChannel> channels() {
        return EnumSet.copyOf(channels);
    }

    /** The id of this template's editable text on one channel, e.g. {@code JOB_STARTED.CUSTOMER.PUSH}. */
    public String idFor(NotificationChannel channel) {
        return key + "." + channel.name();
    }

    /** Admin-facing name of the per-channel template, e.g. "Job started (customer) — Push". */
    public String nameFor(NotificationChannel channel) {
        String label = switch (channel) {
            case PUSH -> "Push";
            case SMS -> "SMS";
            case EMAIL -> "Email";
            case IN_APP -> "In-app";
        };
        return name + " — " + label;
    }

    /**
     * Whether the channel shows a heading. SMS is body-only, so an SMS template has no subject;
     * push and in-app show the title, and email uses it as the subject line.
     */
    public static boolean hasSubject(NotificationChannel channel) {
        return channel != NotificationChannel.SMS;
    }
}
