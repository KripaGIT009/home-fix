package com.homefix.admin.sysconfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory store of platform-wide System Configuration key/value settings. Access is restricted
 * to SUPER_ADMIN at the controller layer (Requirement 19.6, 19.7).
 */
@Component
public class SystemConfigStore {

    private final Map<String, String> settings = new ConcurrentHashMap<>();

    public Map<String, String> all() {
        return Map.copyOf(settings);
    }

    public String get(String key) {
        return settings.get(key);
    }

    /** Sets a value and returns the previous value (or {@code null} if newly created). */
    public String put(String key, String value) {
        return settings.put(key, value);
    }
}
