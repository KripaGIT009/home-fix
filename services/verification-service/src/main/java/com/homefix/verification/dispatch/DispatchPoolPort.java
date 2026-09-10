package com.homefix.verification.dispatch;

import java.util.UUID;

/**
 * Abstraction over the Dispatch Engine's active provider pool (Requirement 5.9). When a
 * provider is suspended they must be removed from the active dispatch pool and any pending
 * job offers to them cancelled. Modelled as a port so the signalling transport (Kafka event
 * via the outbox, direct REST, etc.) can vary without touching the verification workflow, and
 * so it is mockable in unit tests.
 */
public interface DispatchPoolPort {

    /**
     * Removes the provider from the active dispatch pool and cancels any pending job offers to
     * them (Requirement 5.9). Invoked when an APPROVED provider is suspended.
     */
    void deactivateProvider(UUID providerId);
}
