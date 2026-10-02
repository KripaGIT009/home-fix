package com.homefix.admin.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.homefix.admin.sysconfig.SystemSettingStore;

/**
 * In-memory {@link SystemSettingStore} fake for unit tests.
 */
public class InMemorySystemSettingStore implements SystemSettingStore {

    private final Map<String, String> values = new LinkedHashMap<>();
    /** Who last set each key (test-only inspection). */
    public final Map<String, UUID> updatedBy = new LinkedHashMap<>();

    @Override
    public Map<String, String> storedValues() {
        return Map.copyOf(values);
    }

    @Override
    public void save(String key, String value, Instant updatedAt, UUID updatedBy) {
        values.put(key, value);
        this.updatedBy.put(key, updatedBy);
    }

    /** Seeds a stored value directly, bypassing validation (test-only). */
    public InMemorySystemSettingStore with(String key, String value) {
        values.put(key, value);
        return this;
    }
}
