package com.homefix.invoice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-month counter backing the {@code NNNNNN} portion of an invoice number (Requirement 13.4,
 * Property 14). One row per {@code YYYY-MM} period; the {@link #nextValue} is advanced under a
 * pessimistic row lock so concurrent generation on the same period cannot hand out the same
 * sequence number twice.
 */
@Entity
@Table(name = "invoice_sequence")
public class InvoiceSequence {

    /** Period key in {@code YYYY-MM} form, e.g. {@code 2024-07}. */
    @Id
    @Column(name = "period", nullable = false, updatable = false, length = 7)
    private String period;

    /** The next sequence value to hand out for this period (starts at 1). */
    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected InvoiceSequence() {
        // JPA
    }

    public InvoiceSequence(String period, long nextValue) {
        this.period = period;
        this.nextValue = nextValue;
    }

    /** Creates a fresh counter for {@code period} positioned at 1. */
    public static InvoiceSequence startingAt(String period) {
        return new InvoiceSequence(period, 1L);
    }

    /** Returns the current value and advances the counter by one. */
    public long claim() {
        long current = nextValue;
        nextValue = current + 1;
        return current;
    }

    public String getPeriod() {
        return period;
    }

    public long getNextValue() {
        return nextValue;
    }
}
