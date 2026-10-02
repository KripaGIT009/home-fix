package com.homefix.dispatch.domain;

/**
 * Lifecycle of a single job offer (Requirements 8.5-8.7).
 *
 * <ul>
 *   <li>{@link #PENDING} — sent to the provider and awaiting their decision.</li>
 *   <li>{@link #ACCEPTED} / {@link #DECLINED} — the provider decided inside the window.</li>
 *   <li>{@link #EXPIRED} — the window closed without a decision; a late accept is refused.</li>
 *   <li>{@link #WITHDRAWN} — the booking stopped needing a provider (cancelled) while the offer
 *       was still pending.</li>
 * </ul>
 *
 * <p>Only {@code PENDING} can change; every other state is terminal.
 */
public enum OfferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    WITHDRAWN;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
