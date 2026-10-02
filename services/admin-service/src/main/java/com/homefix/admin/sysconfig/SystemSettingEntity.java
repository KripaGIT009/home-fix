package com.homefix.admin.sysconfig;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A System Configuration value a SUPER_ADMIN has set (Requirement 19.2). Only changed settings
 * have a row; a registered setting without one is at its default. The change history lives in the
 * Audit_Log, so this row holds only the current value and who set it.
 */
@Entity
@Table(name = "system_setting")
public class SystemSettingEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = SettingDefinition.MAX_VALUE_LENGTH)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected SystemSettingEntity() {
        // JPA
    }

    public SystemSettingEntity(String key, String value, Instant updatedAt, UUID updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public void change(String value, Instant updatedAt, UUID updatedBy) {
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
