package com.homefix.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A parts or materials line item added by a provider during job execution (Requirement 6.8,
 * 11.3). Records item name, quantity (minimum 1), and unit cost (minimum 0.01). The line total
 * is {@code quantity × unitCost}; the sum of all line totals is submitted to the Pricing
 * Engine for recalculation.
 */
@Entity
@Table(name = "parts_line_item", indexes = {
        @Index(name = "idx_parts_booking", columnList = "booking_id")
})
public class PartsLineItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected PartsLineItem() {
        // JPA
    }

    private PartsLineItem(UUID bookingId, String itemName, int quantity, BigDecimal unitCost, Instant addedAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.addedAt = addedAt;
    }

    public static PartsLineItem of(UUID bookingId, String itemName, int quantity,
                                   BigDecimal unitCost, Instant addedAt) {
        return new PartsLineItem(bookingId, itemName, quantity, unitCost, addedAt);
    }

    /** Line total = quantity × unit cost, scaled to 2 decimals. */
    public BigDecimal lineTotal() {
        return unitCost.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getItemName() {
        return itemName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
