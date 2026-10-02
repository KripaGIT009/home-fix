package com.homefix.notification.domain;

import java.util.Optional;

/**
 * Where the {@link EventTemplateResolver} looks for admin-edited template text before falling
 * back to the built-in text (Requirement 19.2).
 *
 * <p>A port so the resolver stays a pure, directly testable function: production backs it with
 * the {@code notification_template} table, tests and the no-argument resolver use
 * {@link #builtInOnly()}.
 */
@FunctionalInterface
public interface TemplateTextSource {

    /**
     * The stored text for a per-channel template id (e.g. {@code JOB_STARTED.CUSTOMER.PUSH}), or
     * empty to use the built-in text.
     */
    Optional<TemplateText> find(String templateId);

    /** A source with no stored text: every template renders its built-in text. */
    static TemplateTextSource builtInOnly() {
        return templateId -> Optional.empty();
    }
}
