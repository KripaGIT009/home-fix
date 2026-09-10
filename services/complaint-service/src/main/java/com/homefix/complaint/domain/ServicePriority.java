package com.homefix.complaint.domain;

/**
 * Priority of the booking a complaint relates to, which selects the applicable resolution SLA
 * (Requirement 16.4): {@link #EMERGENCY} complaints must be resolved within 24 hours,
 * {@link #STANDARD} complaints within 72 hours.
 */
public enum ServicePriority {
    EMERGENCY,
    STANDARD
}
