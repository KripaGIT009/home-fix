package com.homefix.verification.domain;

import java.util.UUID;

/**
 * A provider's current verification status, projected from the root row alone. Backs the batch
 * status lookup the Provider Service's Admin provider list makes (Requirement 19.2).
 */
public record VerificationStatusView(UUID providerId, VerificationStatus status) {
}
