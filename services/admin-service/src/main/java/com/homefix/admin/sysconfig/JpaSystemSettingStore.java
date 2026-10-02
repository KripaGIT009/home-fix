package com.homefix.admin.sysconfig;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link SystemSettingStore} adapter used in production.
 */
@Component
public class JpaSystemSettingStore implements SystemSettingStore {

    private final SystemSettingRepository repository;

    public JpaSystemSettingStore(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, String> storedValues() {
        return repository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(SystemSettingEntity::getKey, SystemSettingEntity::getValue));
    }

    @Override
    public void save(String key, String value, Instant updatedAt, UUID updatedBy) {
        SystemSettingEntity row = repository.findById(key)
                .orElseGet(() -> new SystemSettingEntity(key, value, updatedAt, updatedBy));
        row.change(value, updatedAt, updatedBy);
        repository.save(row);
    }
}
