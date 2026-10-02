package com.homefix.admin.sysconfig;

import java.util.Locale;
import java.util.Objects;

/**
 * One known System Configuration setting: its identity, how the portal presents it, its default,
 * and the rule a new value must satisfy.
 *
 * @param key          stable key, e.g. {@code audit.pageSize}
 * @param label        short name shown in the portal
 * @param description  what the setting does (shown under the field)
 * @param type         how the value is validated and normalised
 * @param defaultValue the value in effect until a SUPER_ADMIN changes it
 * @param min          inclusive lower bound for a NUMBER (ignored otherwise)
 * @param max          inclusive upper bound for a NUMBER (ignored otherwise)
 * @param maxLength    longest accepted STRING value (ignored otherwise)
 */
public record SettingDefinition(String key,
                                String label,
                                String description,
                                SettingType type,
                                String defaultValue,
                                long min,
                                long max,
                                int maxLength) {

    /** Longest value the {@code system_setting.setting_value} column holds. */
    public static final int MAX_VALUE_LENGTH = 1000;

    public SettingDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (normalise(type, min, max, maxLength, defaultValue) == null) {
            throw new IllegalArgumentException("Default for " + key + " is not a valid value: " + defaultValue);
        }
    }

    /** A whole-number setting within {@code [min, max]}. */
    public static SettingDefinition number(String key, String label, String description,
                                           long defaultValue, long min, long max) {
        return new SettingDefinition(key, label, description, SettingType.NUMBER,
                Long.toString(defaultValue), min, max, 0);
    }

    /** An on/off setting. */
    public static SettingDefinition bool(String key, String label, String description, boolean defaultValue) {
        return new SettingDefinition(key, label, description, SettingType.BOOLEAN,
                Boolean.toString(defaultValue), 0, 0, 0);
    }

    /**
     * The canonical form of a proposed value ({@code "0050"} -> {@code "50"}, {@code "TRUE"} ->
     * {@code "true"}, surrounding whitespace trimmed), or {@code null} if it is not valid.
     */
    public String normalise(String value) {
        return normalise(type, min, max, maxLength, value);
    }

    private static String normalise(SettingType type, long min, long max, int maxLength, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return switch (type) {
            case NUMBER -> {
                try {
                    long number = Long.parseLong(trimmed);
                    yield number < min || number > max ? null : Long.toString(number);
                } catch (NumberFormatException notANumber) {
                    yield null;
                }
            }
            case BOOLEAN -> {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                yield lower.equals("true") || lower.equals("false") ? lower : null;
            }
            case STRING -> trimmed.length() > maxLength ? null : trimmed;
        };
    }

    /** Why a value was refused, for the 400 response. */
    public String rule() {
        return switch (type) {
            case NUMBER -> "a whole number from " + min + " to " + max;
            case BOOLEAN -> "true or false";
            case STRING -> "text of at most " + maxLength + " characters";
        };
    }
}
