package com.homefix.notification.domain;

import java.util.Objects;

/**
 * The editable text of one template on one channel, still carrying its {{placeholders}}.
 *
 * @param subject heading for push / in-app, subject line for email; {@code null} for SMS, or when
 *                the stored row has none (the built-in heading is used then)
 * @param body    the message body
 */
public record TemplateText(String subject, String body) {

    public TemplateText {
        Objects.requireNonNull(body, "body");
    }
}
