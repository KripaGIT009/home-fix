package com.homefix.provider.api.dto;

/**
 * A bank account on file for settlements (Requirement 14.2).
 *
 * <p>The stored value is encrypted at rest and never leaves the service in clear text: only a
 * masked tail is exposed, which is enough for the provider to tell their accounts apart.
 *
 * @param id       stable identifier for the account entry
 * @param masked   masked account reference, e.g. {@code ****4321}
 * @param verified only verified accounts may receive a settlement
 */
public record BankAccountResponse(String id, String masked, boolean verified) {
}
