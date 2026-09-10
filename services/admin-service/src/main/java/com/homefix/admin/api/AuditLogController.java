package com.homefix.admin.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.audit.AuditLogEntry;
import com.homefix.admin.audit.AuditLogStore;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;

/**
 * Audit Logs module (Requirement 19.2). Read-only view over the immutable Audit_Log, filterable
 * by affected entity or by acting Admin. The store is append-only (Requirement 26.10); this
 * controller exposes no write path.
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AuditLogController {

    private final AuditLogStore store;
    private final AdminAuthorization authorization;

    public AuditLogController(AuditLogStore store, AdminAuthorization authorization) {
        this.store = store;
        this.authorization = authorization;
    }

    @GetMapping(params = {"entityType", "entityId"})
    public ResponseEntity<List<AuditLogEntry>> byEntity(@RequestParam("entityType") String entityType,
                                                        @RequestParam("entityId") String entityId,
                                                        Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.AUDIT_LOGS);
        return ResponseEntity.ok(store.findByEntity(entityType, entityId));
    }

    @GetMapping(params = "actorId")
    public ResponseEntity<List<AuditLogEntry>> byActor(@RequestParam("actorId") UUID actorId,
                                                      Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.AUDIT_LOGS);
        return ResponseEntity.ok(store.findByActor(actorId));
    }
}
