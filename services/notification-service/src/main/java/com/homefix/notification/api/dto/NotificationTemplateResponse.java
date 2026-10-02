package com.homefix.notification.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.notification.domain.NotificationChannel;
import com.homefix.notification.template.NotificationTemplateView;

/**
 * A notification template as the Admin Portal reads it ({@code NotificationTemplate} in
 * {@code features/notifications/api.ts}).
 *
 * @param id      per-channel template id, {@code EVENT.AUDIENCE[.VARIANT].CHANNEL}; the path
 *                segment of {@code PUT /admin/notification-templates/{id}}
 * @param key     the template the channel text belongs to, {@code EVENT.AUDIENCE[.VARIANT]}
 * @param subject email subject, or the push / in-app heading; omitted for SMS
 * @param body    the body with its {{placeholders}}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationTemplateResponse(String id,
                                           String key,
                                           String name,
                                           NotificationChannel channel,
                                           String subject,
                                           String body) {

    public static NotificationTemplateResponse from(NotificationTemplateView view) {
        return new NotificationTemplateResponse(view.id(), view.key(), view.name(), view.channel(),
                view.subject(), view.body());
    }
}
