package com.homefix.admin.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.api.dto.AuditLogPageResponse;
import com.homefix.admin.audit.AuditLogEntry;
import com.homefix.admin.audit.AuditLogQueryService;
import com.homefix.admin.audit.AuditLogStore;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;

/**
 * Audit Logs module (Requirement 19.2, 19.8). Read-only view over the immutable Audit_Log. The
 * store is append-only (Requirement 26.10); this controller exposes no write path.
 *
 * <ul>
 *   <li>{@code GET /admin/audit-logs?action=&entityType=&cursor=} — the Admin Portal's view: the
 *       whole log newest first, both filters optional, keyset-paginated with an opaque cursor.</li>
 *   <li>{@code GET /admin/audit-logs?entityType=&entityId=} and {@code ?actorId=} — the full
 *       history of one entity or one Admin, as raw entries. Kept for direct callers; Spring picks
 *       these only when their parameters are all present, which the portal never sends together
 *       ({@code entityId}/{@code actorId}), so they do not shadow the paged view.</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AuditLogController {

    private final AuditLogStore store;
    private final AuditLogQueryService queryService;
    private final AdminAuthorization authorization;

    public AuditLogController(AuditLogStore store, AuditLogQueryService queryService,
                              AdminAuthorization authorization) {
        this.store = store;
        this.queryService = queryService;
        this.authorization = authorization;
    }

    @GetMapping
    public AuditLogPageResponse page(@RequestParam(name = "action", required = false) String action,
                                     @RequestParam(name = "entityType", required = false) String entityType,
                                     @RequestParam(name = "cursor", required = false) String cursor,
                                     Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.AUDIT_LOGS);
        return AuditLogPageResponse.from(queryService.page(action, entityType, cursor));
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
