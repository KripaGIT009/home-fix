package com.homefix.notification.template;

/** No built-in template has the requested id (→ 404). */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String templateId) {
        super("No notification template with id " + templateId);
    }
}
