package com.homefix.admin.sysconfig;

/**
 * Value type of a System Configuration setting, as the Admin Portal renders it
 * ({@code SettingType} in {@code features/system/api.ts}). Values travel as strings either way;
 * the type decides how they are validated and normalised.
 */
public enum SettingType {
    STRING,
    NUMBER,
    BOOLEAN
}
