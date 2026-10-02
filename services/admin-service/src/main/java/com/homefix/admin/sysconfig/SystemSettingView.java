package com.homefix.admin.sysconfig;

/**
 * One setting with its current value (stored, or the default), as the portal lists it.
 */
public record SystemSettingView(String key, String label, String description, SettingType type, String value) {
}
