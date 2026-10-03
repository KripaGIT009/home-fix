package com.homefix.booking.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A Tenant that covered a booking when it entered AWAITING_ASSIGNMENT (a Candidate_Tenant,
 * Requirement MT-4.2), table {@code booking_tenant_candidate} (migration V3).
 *
 * <p>The set is a snapshot taken at queue time (design D5): booking-service stores no coordinates,
 * so re-evaluating coverage on every queue read would mean a Provider Service call per booking, and
 * a Tenant editing its area later must not pull a booking out of a queue it was already shown in.
 * {@code tenant_id} is the Provider Service's id and deliberately not a foreign key, since each
 * service owns its own schema.
 */
@Entity
@Table(name = "booking_tenant_candidate", indexes = {
        @Index(name = "idx_booking_tenant_candidate_tenant", columnList = "tenant_id")
})
@IdClass(BookingTenantCandidate.Key.class)
public class BookingTenantCandidate {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected BookingTenantCandidate() {
        // JPA
    }

    public BookingTenantCandidate(UUID bookingId, UUID tenantId) {
        this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    /** Composite primary key {@code (booking_id, tenant_id)}. */
    public static class Key implements Serializable {

        private UUID bookingId;
        private UUID tenantId;

        public Key() {
        }

        public Key(UUID bookingId, UUID tenantId) {
            this.bookingId = bookingId;
            this.tenantId = tenantId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(bookingId, other.bookingId)
                    && Objects.equals(tenantId, other.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bookingId, tenantId);
        }
    }
}
