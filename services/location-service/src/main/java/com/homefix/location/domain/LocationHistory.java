package com.homefix.location.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Immutable durable record of a single accepted Provider location update, persisted to
 * PostgreSQL so the complete location history of a Booking is available for dispute
 * resolution (Requirement 10.6).
 *
 * <p>One row is written per accepted update; rejected (rate-limited) updates are not
 * persisted.
 */
@Entity
@Table(name = "location_history", indexes = {
        @Index(name = "idx_location_history_booking", columnList = "booking_id, recorded_at")
})
public class LocationHistory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected LocationHistory() {
        // JPA
    }

    private LocationHistory(UUID bookingId, UUID providerId,
                            double latitude, double longitude, Instant recordedAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.providerId = providerId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.recordedAt = recordedAt;
    }

    public static LocationHistory of(UUID bookingId, UUID providerId,
                                     Coordinates coordinates, Instant recordedAt) {
        return new LocationHistory(bookingId, providerId,
                coordinates.latitude(), coordinates.longitude(), recordedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Coordinates getCoordinates() {
        return new Coordinates(latitude, longitude);
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
