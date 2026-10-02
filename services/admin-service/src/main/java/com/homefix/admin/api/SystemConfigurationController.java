package com.homefix.admin.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.admin.api.dto.SystemSettingResponse;
import com.homefix.admin.api.dto.UpdateSystemConfigRequest;
import com.homefix.admin.rbac.AdminAuthorization;
import com.homefix.admin.rbac.AdminModule;
import com.homefix.admin.sysconfig.SystemConfigService;
import com.homefix.admin.sysconfig.SystemSettingView;

/**
 * System Configuration module (Requirement 19.2). Restricted to SUPER_ADMIN: a request from an
 * ADMIN principal is rejected with 403 (Requirement 19.6, 19.7) by
 * {@link AdminAuthorization#requireAccess}, which sees this module is {@code superAdminOnly}.
 * Every configuration change is recorded in the Audit_Log with before/after values
 * (Requirement 19.8) by {@link SystemConfigService}.
 *
 * <p>Only registered settings exist ({@code SystemSettingRegistry}); values are validated per
 * type and a batch is applied all-or-nothing. Every endpoint returns the full settings list in
 * the portal's {@code SystemSetting[]} shape.
 */
@RestController
@RequestMapping("/admin/system-config")
public class SystemConfigurationController {

    private final SystemConfigService systemConfig;
    private final AdminAuthorization authorization;

    public SystemConfigurationController(SystemConfigService systemConfig, AdminAuthorization authorization) {
        this.systemConfig = systemConfig;
        this.authorization = authorization;
    }

    @GetMapping
    public List<SystemSettingResponse> all(Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.SYSTEM_CONFIGURATION);
        return toResponse(systemConfig.all());
    }

    /** Batch update, the Admin Portal's save: {@code {"updates": {"audit.pageSize": "100"}}}. */
    @PutMapping
    public List<SystemSettingResponse> update(@RequestBody UpdateSystemConfigRequest request,
                                              Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.SYSTEM_CONFIGURATION);
        return toResponse(systemConfig.update(request.updates(), AdminPrincipals.actorId(authentication)));
    }

    /** Single-setting update, {@code {"value": "100"}}; kept for direct callers. */
    @PutMapping("/{key}")
    public List<SystemSettingResponse> updateOne(@PathVariable("key") String key,
                                                 @RequestBody Map<String, String> body,
                                                 Authentication authentication) {
        authorization.requireAccess(authentication, AdminModule.SYSTEM_CONFIGURATION);
        // Not Map.of, which rejects null: a missing "value" must reach validation and come back
        // as a 400 for this key, not a 500.
        Map<String, String> update = new LinkedHashMap<>();
        update.put(key, body.get("value"));
        return toResponse(systemConfig.update(update, AdminPrincipals.actorId(authentication)));
    }

    private static List<SystemSettingResponse> toResponse(List<SystemSettingView> settings) {
        return settings.stream().map(SystemSettingResponse::from).toList();
    }
}
