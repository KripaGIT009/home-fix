package com.homefix.provider.service;

import java.math.BigDecimal;

/**
 * Validated intent to request a settlement of {@code amount} to a verified bank account
 * (Requirement 14.2, 4.9). The raw bank account reference is encrypted before persistence.
 */
public record SettlementCommand(BigDecimal amount, String bankAccountRef) {
}
