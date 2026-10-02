package com.homefix.admin.api.dto;

import com.homefix.admin.sysconfig.SettingType;
import com.homefix.admin.sysconfig.SystemSettingView;

/**
 * One System Configuration setting as the Admin Portal reads it ({@code SystemSetting} in
 * {@code features/system/api.ts}). The value is always a string; {@code type} says how to edit it.
 */
public record SystemSettingResponse(String key, String label, String description, SettingType type, String value) {

    public static SystemSettingResponse from(SystemSettingView view) {
        return new SystemSettingResponse(view.key(), view.label(), view.description(), view.type(), view.value());
    }
}
