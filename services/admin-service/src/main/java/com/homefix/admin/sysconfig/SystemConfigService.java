package com.homefix.admin.sysconfig;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.admin.audit.AuditLogService;

/**
 * System Configuration (Requirement 19.2): the registered settings with their current values, and
 * the SUPER_ADMIN batch update. Access control is the controller's ({@code AdminAuthorization},
 * Requirement 19.6, 19.7).
 *
 * <p>An update is all-or-nothing: every key and value is validated before anything is written,
 * and the value writes and their Audit_Log entries share one transaction, so a failure leaves
 * neither a partial change nor an unaudited one. Each changed setting gets its own audit entry
 * (entity {@code SYSTEM_CONFIG}, id = key) with its before and after value (Requirement 19.8); a
 * value submitted unchanged is neither written nor audited.
 */
@Service
public class SystemConfigService {

    /** Audit_Log entity type for setting changes. */
    public static final String ENTITY_TYPE = "SYSTEM_CONFIG";

    private final SystemSettingStore store;
    private final AuditLogService auditLog;
    private final Clock clock;

    public SystemConfigService(SystemSettingStore store, AuditLogService auditLog, Clock clock) {
        this.store = store;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Every registered setting with its current value, in display order. */
    @Transactional(readOnly = true)
    public List<SystemSettingView> all() {
        Map<String, String> stored = store.storedValues();
        return SystemSettingRegistry.all().stream()
                .map(definition -> view(definition, currentValue(definition, stored)))
                .toList();
    }

    /**
     * Applies a batch of changes, then returns every setting.
     *
     * @throws SystemConfigValidationException if any key is unknown or any value invalid; nothing
     *                                         is changed in that case
     */
    @Transactional
    public List<SystemSettingView> update(Map<String, String> updates, UUID actorId) {
        if (updates == null) {
            throw new SystemConfigValidationException(List.of("updates: is required"));
        }
        List<String> problems = new ArrayList<>();
        Map<SettingDefinition, String> accepted = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            SettingDefinition definition = SystemSettingRegistry.find(key).orElse(null);
            if (definition == null) {
                problems.add(key + ": unknown setting");
                return;
            }
            String normalised = definition.normalise(value);
            if (normalised == null) {
                problems.add(key + ": must be " + definition.rule());
            } else {
                accepted.put(definition, normalised);
            }
        });
        if (!problems.isEmpty()) {
            throw new SystemConfigValidationException(problems);
        }

        Map<String, String> stored = store.storedValues();
        accepted.forEach((definition, newValue) -> {
            String previous = currentValue(definition, stored);
            if (Objects.equals(previous, newValue)) {
                return;
            }
            store.save(definition.key(), newValue, clock.instant(), actorId);
            auditLog.recordUpdate(actorId, ENTITY_TYPE, definition.key(),
                    singleton(definition.key(), previous), singleton(definition.key(), newValue));
        });
        return all();
    }

    /** The current value of a NUMBER setting. */
    public long numberValue(SettingDefinition definition) {
        return Long.parseLong(currentValue(definition, store.storedValues()));
    }

    /** The current value of a BOOLEAN setting. */
    public boolean booleanValue(SettingDefinition definition) {
        return Boolean.parseBoolean(currentValue(definition, store.storedValues()));
    }

    /**
     * The stored value, or the default when there is none. A stored value that no longer
     * satisfies the setting's rule (the rule was tightened after it was saved) reads as the
     * default rather than leaking an out-of-range number into the code that uses it.
     */
    private static String currentValue(SettingDefinition definition, Map<String, String> stored) {
        String normalised = definition.normalise(stored.get(definition.key()));
        return normalised != null ? normalised : definition.defaultValue();
    }

    private static SystemSettingView view(SettingDefinition definition, String value) {
        return new SystemSettingView(definition.key(), definition.label(), definition.description(),
                definition.type(), value);
    }

    private static Map<String, Object> singleton(String key, String value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
