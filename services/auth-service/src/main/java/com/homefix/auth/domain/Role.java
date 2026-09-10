package com.homefix.auth.domain;

/**
 * Platform roles. A single user account may hold multiple roles simultaneously
 * (e.g. both CUSTOMER and SERVICE_PROVIDER) per Requirement 1.14.
 */
public enum Role {
    CUSTOMER,
    SERVICE_PROVIDER,
    ADMIN
}
