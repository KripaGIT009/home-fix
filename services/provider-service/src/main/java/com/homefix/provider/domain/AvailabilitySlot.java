package com.homefix.provider.domain;

import java.time.DayOfWeek;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A general-availability slot at 1-hour granularity (Requirement 4.5).
 *
 * <p>A slot covers the half-open interval {@code [startHour, endHour)} on a given day of week.
 * {@code startHour} is in {@code [0,23]} and {@code endHour} in {@code [1,24]} with
 * {@code endHour > startHour}. Overlap detection is enforced at the service layer.
 */
@Entity
@Table(name = "availability_slot")
public class AvailabilitySlot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private ProviderProfile provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_hour", nullable = false)
    private int startHour;

    @Column(name = "end_hour", nullable = false)
    private int endHour;

    protected AvailabilitySlot() {
        // JPA
    }

    public AvailabilitySlot(DayOfWeek dayOfWeek, int startHour, int endHour) {
        this.id = UUID.randomUUID();
        this.dayOfWeek = dayOfWeek;
        this.startHour = startHour;
        this.endHour = endHour;
    }

    void attachTo(ProviderProfile provider) {
        this.provider = provider;
    }

    /**
     * @return {@code true} if this slot and {@code other} fall on the same day and their
     *         hour intervals overlap (share at least one hour).
     */
    public boolean overlaps(AvailabilitySlot other) {
        return this.dayOfWeek == other.dayOfWeek
                && this.startHour < other.endHour
                && other.startHour < this.endHour;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartHour() {
        return startHour;
    }

    public int getEndHour() {
        return endHour;
    }
}
