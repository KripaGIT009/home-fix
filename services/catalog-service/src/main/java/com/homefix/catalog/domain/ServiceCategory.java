package com.homefix.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Service category aggregate (Requirement 3, mirrors the {@code SERVICE_CATEGORY} data model).
 *
 * <p>Categories are database-driven with no values hardcoded in application logic
 * (Requirement 3.1). Deactivating a category (rather than deleting it) excludes it from
 * customer-facing listings while preserving existing bookings (Requirement 3.5).
 */
@Entity
@Table(name = "service_category")
public class ServiceCategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "icon_url", length = 512)
    private String iconUrl;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected ServiceCategory() {
        // JPA
    }

    private ServiceCategory(UUID id, String name, String description, String iconUrl, int displayOrder) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.iconUrl = iconUrl;
        this.displayOrder = displayOrder;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Factory for a brand-new, active category (Requirement 3.2). */
    public static ServiceCategory create(String name, String description, String iconUrl, int displayOrder) {
        return new ServiceCategory(UUID.randomUUID(), name, description, iconUrl, displayOrder);
    }

    public void update(String name, String description, String iconUrl, int displayOrder) {
        this.name = name;
        this.description = description;
        this.iconUrl = iconUrl;
        this.displayOrder = displayOrder;
        touch();
    }

    /** Activates the category so it reappears in customer-facing listings. */
    public void activate() {
        this.active = true;
        touch();
    }

    /**
     * Deactivates the category (Requirement 3.5). Excluded from customer-facing listings and
     * blocks new bookings for its subcategories; existing bookings are unaffected.
     */
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

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public int getDisplayOrder() {
        return displayOrder;
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
