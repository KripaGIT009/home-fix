package com.homefix.admin.api.dto;

import java.util.Map;

/**
 * Body of {@code PUT /admin/system-config}: the settings to change, key to new value. Applied
 * all-or-nothing; validation is the {@code SystemConfigService}'s so every problem is reported
 * together.
 */
public record UpdateSystemConfigRequest(Map<String, String> updates) {
}
