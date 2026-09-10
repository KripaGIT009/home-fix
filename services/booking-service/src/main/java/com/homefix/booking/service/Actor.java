package com.homefix.booking.service;

import java.util.UUID;

/**
 * The actor responsible for a state transition, recorded in the audit trail (Requirement
 * 9.15). Human actors carry a user id and role; system-initiated transitions carry a null id
 * and the originating service name as the role.
 */
public record Actor(UUID id, String role) {

    public Actor {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("actor role must not be blank");
        }
    }

    /** A human actor with a user id and role (e.g. CUSTOMER, SERVICE_PROVIDER, ADMIN). */
    public static Actor user(UUID id, String role) {
        if (id == null) {
            throw new IllegalArgumentException("user actor id must not be null");
        }
        return new Actor(id, role);
    }

    /** A system-initiated actor identified by the service name (Requirement 9.9, 9.13). */
    public static Actor system() {
        return new Actor(null, "booking-service");
    }
}
