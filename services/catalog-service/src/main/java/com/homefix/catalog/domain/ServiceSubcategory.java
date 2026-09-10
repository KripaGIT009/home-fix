package com.homefix.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Service subcategory aggregate (Requirement 3, mirrors the {@code SERVICE_SUBCATEGORY} data
 * model). Belongs to a parent {@link ServiceCategory} and carries the configurable pricing and
 * scheduling attributes used across the platform (Requirement 3.7).
 */
@Entity
@Table(name = "service_subcategory")
public class ServiceSubcategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "estimated_duration_min", nullable = false)
    private int estimatedDurationMin;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "service_subcategory_skill_tag",
            joinColumns = @JoinColumn(name = "subcategory_id"))
    @Column(name = "tag", nullable = false, length = 64)
    private List<String> skillTags = new ArrayList<>();

    @Column(name = "emergency_available", nullable = false)
    private boolean emergencyAvailable;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected ServiceSubcategory() {
        // JPA
    }

    private ServiceSubcategory(UUID id, UUID categoryId, String name, BigDecimal basePrice,
                               int estimatedDurationMin, List<String> skillTags, boolean emergencyAvailable) {
        this.id = id;
        this.categoryId = categoryId;
        this.name = name;
        this.basePrice = basePrice;
        this.estimatedDurationMin = estimatedDurationMin;
        this.skillTags = new ArrayList<>(skillTags);
        this.emergencyAvailable = emergencyAvailable;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Factory for a brand-new, active subcategory (Requirement 3.3). */
    public static ServiceSubcategory create(UUID categoryId, String name, BigDecimal basePrice,
                                            int estimatedDurationMin, List<String> skillTags,
                                            boolean emergencyAvailable) {
        return new ServiceSubcategory(UUID.randomUUID(), categoryId, name, basePrice,
                estimatedDurationMin, skillTags, emergencyAvailable);
    }

    public void update(String name, BigDecimal basePrice, int estimatedDurationMin,
                       List<String> skillTags, boolean emergencyAvailable) {
        this.name = name;
        this.basePrice = basePrice;
        this.estimatedDurationMin = estimatedDurationMin;
        this.skillTags = new ArrayList<>(skillTags);
        this.emergencyAvailable = emergencyAvailable;
        touch();
    }

    public void activate() {
        this.active = true;
        touch();
    }

    public void deactivate() {
        this.active = false;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ----- Accessors -----

    public UUID getId() {
        return id;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public int getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public List<String> getSkillTags() {
        return skillTags;
    }

    public boolean isEmergencyAvailable() {
        return emergencyAvailable;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
