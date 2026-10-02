package com.homefix.admin.sysconfig;

import java.util.List;

/**
 * A System Configuration update was refused (-> 400): an unknown key or a value that does not
 * satisfy its setting's rule. Carries every problem found; nothing was changed.
 */
public class SystemConfigValidationException extends RuntimeException {

    private final List<String> problems;

    public SystemConfigValidationException(List<String> problems) {
        super("System configuration update rejected: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}
