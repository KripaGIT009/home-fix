package com.homefix.notification.template;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the admin-editable notification templates. The table is small (one
 * row per built-in template and channel), so callers read it whole.
 */
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplateEntity, String> {
}
