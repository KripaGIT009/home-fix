package com.homefix.admin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;
import com.homefix.admin.sysconfig.SystemConfigStore;

/**
 * System Configuration module (Requirement 19.2). Restricted to SUPER_ADMIN: a request from an
 * ADMIN principal is rejected with 403 (Requirement 19.6, 19.7) by
 * {@link AdminAuthorization#requireAccess}, which sees this module is {@code superAdminOnly}.
 * Every configuration change is recorded in the Audit_Log with before/after values
 * (Requirement 19.8).
 */
@RestController
@RequestMapping("/admin/system-config")
public class SystemConfigurationController {

    private static final String ENTITY_TYPE = "SYSTEM_CONFIG";

    private final SystemConfigStore store;
    private final AdminAuthorization authorization;
    private final AuditLogService auditLog;

    public SystemConfigurationController(SystemConfigStore store,
                                         AdminAuthorization authorization,
                                         AuditLogService auditLog) {
        this.store = store;
        this.authorization = authorization;
        this.auditLog = auditLog;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> all(Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.SYSTEM_CONFIGURATION);
        return ResponseEntity.ok(store.all());
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, String>> update(@PathVariable("key") String key,
                                                       @RequestBody Map<String, String> body,
                                                       Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.SYSTEM_CONFIGURATION);

        String newValue = body.get("value");
        String previous = store.put(key, newValue);

        UUID actorId = AdminPrincipals.actorId(authentication);
        Map<String, Object> before = previous == null ? null : singleton(key, previous);
        auditLog.recordUpdate(actorId, ENTITY_TYPE, key, before, singleton(key, newValue));

        return ResponseEntity.ok(store.all());
    }

    private static Map<String, Object> singleton(String key, String value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
