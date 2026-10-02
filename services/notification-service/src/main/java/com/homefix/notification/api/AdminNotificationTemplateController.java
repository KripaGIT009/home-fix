package com.homefix.notification.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.notification.api.dto.NotificationTemplateResponse;
import com.homefix.notification.api.dto.UpdateNotificationTemplateRequest;
import com.homefix.notification.template.NotificationTemplateService;

/**
 * Admin Portal Notification Templates module (Requirement 19.2): lists the per-channel message
 * templates and lets an admin edit their subject and body.
 *
 * <p>Only ADMIN and SUPER_ADMIN reach this controller ({@code NotificationRbacConfig}). The gateway
 * routes {@code /admin/notification-templates/**} here unchanged.
 */
@RestController
@RequestMapping("/admin/notification-templates")
public class AdminNotificationTemplateController {

    private final NotificationTemplateService templateService;

    public AdminNotificationTemplateController(NotificationTemplateService templateService) {
        this.templateService = templateService;
    }

    /** Every template, one entry per event/audience and channel, in event lifecycle order. */
    @GetMapping
    public List<NotificationTemplateResponse> list() {
        return templateService.list().stream()
                .map(NotificationTemplateResponse::from)
                .toList();
    }

    /** Replaces a template's body (and subject, when given); 400 on an unknown placeholder. */
    @PutMapping("/{id}")
    public NotificationTemplateResponse update(@PathVariable("id") String id,
                                               @RequestBody UpdateNotificationTemplateRequest request,
                                               Authentication authentication) {
        return NotificationTemplateResponse.from(
                templateService.update(id, request.subject(), request.body(), actorId(authentication)));
    }

    /**
     * The editing admin's user id: the shared {@code JwtValidationFilter} sets the authentication
     * name to the JWT subject. Recorded as {@code updated_by}; null if it is not a UUID.
     */
    private static UUID actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
