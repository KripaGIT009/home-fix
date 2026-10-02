package com.homefix.admin.sysconfig;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence port for System Configuration values. Production wires a JPA-backed adapter so
 * values survive a restart; tests use an in-memory fake.
 */
public interface SystemSettingStore {

    /** The stored value of every setting that has been changed from its default, by key. */
    Map<String, String> storedValues();

    /** Stores (inserts or replaces) one setting's value. */
    void save(String key, String value, Instant updatedAt, UUID updatedBy);
}
