package com.homefix.customer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer's saved address (design {@code ADDRESS}).
 *
 * <p>The resolved street address is PII and is stored only as an AES-256 ciphertext in
 * {@code address_text_encrypted} (Requirement 2.7). Raw GPS coordinates are stored so
 * that a booking can still proceed when reverse-geocoding fails (Requirement 2.2).
 */
@Entity
@Table(name = "address")
public class Address {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    /** Reverse-geocoded street address, encrypted at rest (PII). Null if geocoding failed. */
    @Column(name = "address_text_encrypted", length = 4096)
    private String addressTextEncrypted;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Address() {
        // JPA
    }

    private Address(UUID id, UUID customerId, String label, double lat, double lng,
                    String addressTextEncrypted, boolean isDefault) {
        this.id = id;
        this.customerId = customerId;
        this.label = label;
        this.lat = lat;
        this.lng = lng;
        this.addressTextEncrypted = addressTextEncrypted;
        this.isDefault = isDefault;
        this.isActive = true;
        this.createdAt = Instant.now();
    }

    public static Address create(UUID customerId, String label, double lat, double lng,
                                 String addressTextEncrypted, boolean isDefault) {
        return new Address(UUID.randomUUID(), customerId, label, lat, lng,
                addressTextEncrypted, isDefault);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLabel() {
        return label;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public String getAddressTextEncrypted() {
        return addressTextEncrypted;
    }

    /** True when reverse-geocoding resolved and stored an encrypted street address. */
    public boolean isGeocoded() {
        return addressTextEncrypted != null;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public boolean isActive() {
        return isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
