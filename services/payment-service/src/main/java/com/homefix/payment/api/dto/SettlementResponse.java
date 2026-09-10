package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.payment.domain.Settlement;
import com.homefix.payment.domain.SettlementStatus;

/** API view of a {@link Settlement}. Never exposes the encrypted bank account reference. */
public record SettlementResponse(
        UUID id,
        UUID providerId,
        BigDecimal amount,
        SettlementStatus status,
        Instant requestedAt,
        Instant updatedAt) {

    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
                s.getId(), s.getProviderId(), s.getAmount(), s.getStatus(),
                s.getRequestedAt(), s.getUpdatedAt());
    }
}
