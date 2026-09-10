package com.homefix.admin.module;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.homefix.admin.rbac.AdminModule;

/**
 * Placeholder {@link ModuleActionPort} that echoes the payload back as the resulting state. The
 * per-module HTTP adapters over the owning services are wired in a later integration task; this
 * keeps the Admin Service independently buildable and lets the audit-logging flow be exercised.
 */
@Component
public class StubModuleActionAdapter implements ModuleActionPort {

    @Override
    public Map<String, Object> apply(AdminModule module, String action, String entityId,
                                     Map<String, Object> payload) {
        return payload == null ? Map.of() : Map.copyOf(payload);
    }

    @Override
    public Map<String, Object> currentState(AdminModule module, String entityId) {
        return Map.of();
    }
}
