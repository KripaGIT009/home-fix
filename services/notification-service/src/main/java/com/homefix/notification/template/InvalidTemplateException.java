package com.homefix.notification.template;

import java.util.List;

/**
 * A template edit was rejected (→ 400): an unknown or malformed placeholder, a subject on a
 * channel that has none, or text that is blank or too long. Carries every problem found so the
 * admin can fix them in one go.
 */
public class InvalidTemplateException extends RuntimeException {

    private final List<String> problems;

    public InvalidTemplateException(String templateId, List<String> problems) {
        super("Template " + templateId + " is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}
