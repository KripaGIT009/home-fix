package com.homefix.admin.sysconfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The System Configuration settings the platform knows about (Requirement 19.2).
 *
 * <p>Deliberately small: every setting listed here is read by code in this service, so changing it
 * has the effect its description promises. A key that is not registered is refused rather than
 * stored, since nothing would read it. Settings owned by other services (pricing, dispatch
 * weights, ...) are configured in those services' own Admin Portal modules, not here.
 */
public final class SystemSettingRegistry {

    /** Entries per page of {@code GET /admin/audit-logs}. */
    public static final SettingDefinition AUDIT_PAGE_SIZE = SettingDefinition.number(
            "audit.pageSize",
            "Audit log page size",
            "Number of entries per page in the Audit Logs view.",
            50, 10, 200);

    /** Whether audit change summaries include before/after values or only field names. */
    public static final SettingDefinition AUDIT_SUMMARY_SHOWS_VALUES = SettingDefinition.bool(
            "audit.summaryShowsValues",
            "Show values in audit summaries",
            "When on, the Audit Logs change summary shows each changed field's before and after "
                    + "values; when off, it lists only the names of the changed fields.",
            true);

    private static final List<SettingDefinition> ALL = List.of(AUDIT_PAGE_SIZE, AUDIT_SUMMARY_SHOWS_VALUES);

    private static final Map<String, SettingDefinition> BY_KEY = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(SettingDefinition::key, Function.identity()));

    private SystemSettingRegistry() {
    }

    /** Every known setting, in display order. */
    public static List<SettingDefinition> all() {
        return ALL;
    }

    public static Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
