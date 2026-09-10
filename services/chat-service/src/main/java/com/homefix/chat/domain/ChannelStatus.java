package com.homefix.chat.domain;

/**
 * Lifecycle state of a booking's chat channel (Requirement 18.1, 18.5, 18.6).
 *
 * <ul>
 *   <li>{@link #ACTIVE} — activated on PROVIDER_ACCEPTED; messages may be sent and read.</li>
 *   <li>{@link #DEACTIVATED} — closed on PAYMENT_COMPLETED or CANCELLED; sends are rejected while
 *       historical reads remain available for the retention window.</li>
 * </ul>
 */
public enum ChannelStatus {
    ACTIVE,
    DEACTIVATED
}
