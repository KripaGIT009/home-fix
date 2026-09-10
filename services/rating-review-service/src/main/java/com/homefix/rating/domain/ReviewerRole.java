package com.homefix.rating.domain;

/**
 * Which party authored a review. A completed booking opens a prompt for both directions
 * (Requirement 15.1, 15.10):
 * <ul>
 *   <li>{@link #CUSTOMER} — the customer rating the provider on five dimensions (Requirement 15.3).</li>
 *   <li>{@link #PROVIDER} — the provider rating the customer on a single 1–5 scale (Requirement 15.10).</li>
 * </ul>
 * Only customer-to-provider reviews contribute to a provider's aggregate rating (Requirement 15.4).
 */
public enum ReviewerRole {
    CUSTOMER,
    PROVIDER
}
