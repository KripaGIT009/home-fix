package com.homefix.provider.domain;

/**
 * Lifecycle status of a settlement request (Requirement 14.3).
 */
public enum SettlementStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
