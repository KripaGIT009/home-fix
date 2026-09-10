package com.homefix.complaint.domain;

/**
 * Lifecycle state of a complaint (Requirement 16).
 *
 * <ul>
 *   <li>{@link #OPEN} — created and assigned to a Support_Agent (16.1).</li>
 *   <li>{@link #IN_PROGRESS} — a Support_Agent is actively working the complaint.</li>
 *   <li>{@link #ESCALATED} — the resolution SLA was breached and the complaint was escalated to a
 *       Senior_Support_Agent (16.4).</li>
 *   <li>{@link #DISPUTED} — the agent set the booking to DISPUTED, placing a hold on the provider
 *       settlement until the complaint closes (16.7).</li>
 *   <li>{@link #REFUND_FAILED} — a refund request was rejected by the Payment Service (16.6).</li>
 *   <li>{@link #RESOLVED} — the complaint was resolved; any settlement hold is released (16.8).</li>
 *   <li>{@link #CLOSED} — the complaint was closed; any settlement hold is released (16.8).</li>
 * </ul>
 *
 * <p>{@link #RESOLVED} and {@link #CLOSED} are terminal; both release an active settlement hold.
 */
public enum ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    ESCALATED,
    DISPUTED,
    REFUND_FAILED,
    RESOLVED,
    CLOSED;

    /** Whether this is a terminal state (the complaint is finished and any hold is released). */
    public boolean isTerminal() {
        return this == RESOLVED || this == CLOSED;
    }
}
