package com.homefix.admin.module;

import java.util.Map;

import com.homefix.admin.rbac.AdminModule;

/**
 * Port over the downstream service that actually owns a given operational module's data. The
 * Admin Service coordinates and audits Admin actions; the state change itself is applied by the
 * owning service (per-service DB isolation). A production adapter dispatches to the correct
 * service API by module; tests supply a fake.
 */
public interface ModuleActionPort {

    /**
     * Applies an Admin action to the owning service and returns the resulting entity field values
     * (used as the audit {@code afterValues}). For a delete, the returned map may be empty.
     *
     * @param module    the operational module the action targets
     * @param action    create/update/delete/approve/reject
     * @param entityId  the affected entity's identifier (may be null for create)
     * @param payload   the requested field values
     * @return the entity's resulting field values
     */
    Map<String, Object> apply(AdminModule module, String action, String entityId, Map<String, Object> payload);

    /**
     * Returns the entity's current field values before an action is applied (used as the audit
     * {@code beforeValues}). Returns an empty map for a not-yet-existing entity (create).
     */
    Map<String, Object> currentState(AdminModule module, String entityId);
}
