package com.homefix.admin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.api.dto.DispatchWeightsRequest;
import com.homefix.admin.audit.AuditLogService;
import com.homefix.admin.dispatch.DispatchWeights;
import com.homefix.admin.dispatch.DispatchWeightsStore;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;

import jakarta.validation.Valid;

/**
 * Dispatch Rule Configuration module (Requirement 19.2, 19.5). Exposes the current dispatch
 * matching weights and an update endpoint that validates each weight ∈ [0.0, 1.0] and that the
 * five sum to exactly 1.0. On any violation the update is rejected (via
 * {@code WeightValidationException} → 400) and the existing weights are left unchanged. A
 * successful update is recorded in the Audit_Log with before/after values (Requirement 19.8).
 */
@RestController
@RequestMapping("/admin/dispatch/weights")
public class DispatchRuleController {

    private static final String ENTITY_TYPE = "DISPATCH_WEIGHTS";
    /** Singleton config; a fixed key identifies the single dispatch-weight configuration. */
    private static final String ENTITY_ID = "GLOBAL";

    private final DispatchWeightsStore store;
    private final AdminAuthorization authorization;
    private final AuditLogService auditLog;

    public DispatchRuleController(DispatchWeightsStore store,
                                  AdminAuthorization authorization,
                                  AuditLogService auditLog) {
        this.store = store;
        this.authorization = authorization;
        this.auditLog = auditLog;
    }

    @GetMapping
    public ResponseEntity<DispatchWeights> current(Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.DISPATCH_RULE_CONFIGURATION);
        return ResponseEntity.ok(store.current());
    }

    @PutMapping
    public ResponseEntity<DispatchWeights> update(@Valid @RequestBody DispatchWeightsRequest request,
                                                  Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.DISPATCH_RULE_CONFIGURATION);

        DispatchWeights before = store.current();
        // ofExact validates range + exact sum; on failure it throws BEFORE the store is touched,
        // satisfying "leave existing weights unchanged" (Requirement 19.5).
        DispatchWeights candidate = DispatchWeights.ofExact(
                request.distanceWeight(),
                request.availabilityWeight(),
                request.ratingWeight(),
                request.skillWeight(),
                request.performanceWeight());
        DispatchWeights after = store.update(candidate);

        UUID actorId = AdminPrincipals.actorId(authentication);
        auditLog.recordUpdate(actorId, ENTITY_TYPE, ENTITY_ID, toMap(before), toMap(after));

        return ResponseEntity.ok(after);
    }

    private static Map<String, Object> toMap(DispatchWeights w) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("distanceWeight", w.distanceWeight());
        map.put("availabilityWeight", w.availabilityWeight());
        map.put("ratingWeight", w.ratingWeight());
        map.put("skillWeight", w.skillWeight());
        map.put("performanceWeight", w.performanceWeight());
        return map;
    }
}
