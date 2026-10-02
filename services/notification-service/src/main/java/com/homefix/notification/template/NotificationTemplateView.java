package com.homefix.notification.template;

import com.homefix.notification.domain.NotificationChannel;

/**
 * One per-channel template as an admin sees it: the built-in identity plus the current text
 * (stored, or built-in when no row exists).
 *
 * @param subject heading / email subject; {@code null} for SMS, which has none
 */
public record NotificationTemplateView(String id, String key, String name, NotificationChannel channel,
                                       String subject, String body) {
}
