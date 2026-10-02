package com.homefix.verification.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /internal/verifications/approved}: the requested ids whose verification
 * is currently {@code APPROVED}. Ids that are not approved, or have no verification record at all,
 * are simply absent — the response never says <em>why</em> a provider is not approved.
 */
public record ApprovedProvidersResponse(List<UUID> approvedProviderIds) {
}
