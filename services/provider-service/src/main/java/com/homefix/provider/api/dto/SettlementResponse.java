package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.provider.domain.Settlement;

/**
 * Read model for a settlement request. Never exposes the encrypted bank account reference.
 */
public record SettlementResponse(
        UUID id,
        UUID providerId,
        BigDecimal amount,
        String status,
        Instant requestedAt) {

    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
                s.getId(),
                s.getProviderId(),
                s.getAmount(),
                s.getStatus().name(),
                s.getRequestedAt());
    }
}
