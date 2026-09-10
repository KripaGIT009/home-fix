package com.homefix.admin.rbac;

/**
 * Raised when an Admin principal attempts to access a module their role does not permit
 * (Requirement 19.7). Surfaced by the REST layer as HTTP 403 Forbidden.
 */
public class ModuleAccessDeniedException extends RuntimeException {

    private final AdminModule module;

    public ModuleAccessDeniedException(AdminModule module) {
        super("Role is not permitted to access module " + module.name());
        this.module = module;
    }

    public AdminModule getModule() {
        return module;
    }
}
