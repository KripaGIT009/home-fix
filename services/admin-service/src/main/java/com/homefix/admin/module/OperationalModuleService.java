package com.homefix.admin.module;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.homefix.admin.api.AdminPrincipals;
import com.homefix.admin.audit.AdminAction;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;

/**
 * Applies an Admin action against an operational module, enforcing module RBAC first
 * (Requirement 19.6, 19.7) and recording the action in the Audit_Log with before/after values
 * (Requirement 19.8):
 *
 * <ul>
 *   <li>CREATE — audit {@code afterValues} = initial field values.</li>
 *   <li>UPDATE/APPROVE/REJECT — audit before + after values.</li>
 *   <li>DELETE — audit {@code beforeValues} = field values at time of deletion.</li>
 * </ul>
 *
 * <p>Because RBAC is checked before the port is invoked, an unauthorised action (e.g. ADMIN
 * against System Configuration) produces neither a state change nor an audit entry — it is
 * rejected with 403.
 */
@Service
public class OperationalModuleService {

    private final ModuleActionPort port;
    private final AdminAuthorization authorization;
    private final AuditLogService auditLog;

    public OperationalModuleService(ModuleActionPort port,
                                    AdminAuthorization authorization,
                                    AuditLogService auditLog) {
        this.port = port;
        this.authorization = authorization;
        this.auditLog = auditLog;
    }

    public Map<String, Object> create(Authentication auth, AdminModule module, String entityType,
                                       String entityId, Map<String, Object> payload) {
        return perform(auth, module, AdminAction.CREATE, entityType, entityId, payload);
    }

    public Map<String, Object> update(Authentication auth, AdminModule module, String entityType,
                                       String entityId, Map<String, Object> payload) {
        return perform(auth, module, AdminAction.UPDATE, entityType, entityId, payload);
    }

    public Map<String, Object> delete(Authentication auth, AdminModule module, String entityType,
                                       String entityId) {
        return perform(auth, module, AdminAction.DELETE, entityType, entityId, null);
    }

    public Map<String, Object> approve(Authentication auth, AdminModule module, String entityType,
                                        String entityId, Map<String, Object> payload) {
        return perform(auth, module, AdminAction.APPROVE, entityType, entityId, payload);
    }

    public Map<String, Object> reject(Authentication auth, AdminModule module, String entityType,
                                       String entityId, Map<String, Object> payload) {
        return perform(auth, module, AdminAction.REJECT, entityType, entityId, payload);
    }

    private Map<String, Object> perform(Authentication auth, AdminModule module, AdminAction action,
                                        String entityType, String entityId, Map<String, Object> payload) {
        authorization.requireAccess(auth, module);
        UUID actorId = AdminPrincipals.actorId(auth);

        Map<String, Object> before = action == AdminAction.CREATE
                ? null
                : port.currentState(module, entityId);
        Map<String, Object> after = port.apply(module, action.name(), entityId, payload);

        switch (action) {
            case CREATE -> auditLog.recordCreate(actorId, entityType, entityId, after);
            case DELETE -> auditLog.recordDelete(actorId, entityType, entityId, before);
            default -> auditLog.record(actorId, action, entityType, entityId, before, after);
        }
        return after;
    }
}
