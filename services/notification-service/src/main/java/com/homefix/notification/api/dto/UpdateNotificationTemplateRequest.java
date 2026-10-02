package com.homefix.notification.api.dto;

/**
 * Body of {@code PUT /admin/notification-templates/{id}} ({@code UpdateTemplatePayload} in the
 * Admin Portal). Validation (blank text, length, placeholders, subject on SMS) is the
 * {@code NotificationTemplateService}'s, so every problem is reported together.
 *
 * @param subject new subject / heading; omitted to keep the current one
 * @param body    new body
 */
public record UpdateNotificationTemplateRequest(String subject, String body) {
}
