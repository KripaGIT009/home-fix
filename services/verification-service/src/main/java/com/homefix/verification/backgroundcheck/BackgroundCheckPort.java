package com.homefix.verification.backgroundcheck;

import java.util.UUID;

/**
 * Abstraction over the external background-check provider (Requirement 5.4, 5.5). Modelled as
 * a port so the concrete vendor integration can vary without touching the verification
 * workflow and so it is mockable in unit tests.
 */
public interface BackgroundCheckPort {

    /**
     * Initiates a background check for the given provider. Returns an opaque vendor reference
     * that can be correlated with the eventual result callback.
     *
     * @param providerId the provider under verification
     * @return an opaque vendor reference for the initiated check
     */
    String initiate(UUID providerId);
}
