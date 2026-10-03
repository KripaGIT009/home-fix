package com.homefix.provider.api.dto;

import java.util.UUID;

import com.homefix.provider.bank.BankAccountView;

/**
 * A bank account on file for settlements (Requirement 14.2).
 *
 * <p>The stored value is encrypted at rest and never leaves the service in clear text: only a
 * masked label computed from the decrypted number is exposed, which is enough for the provider to
 * recognise the account.
 *
 * @param id         stable identifier for the account entry (the provider profile id: a provider
 *                   has at most one account)
 * @param masked     masked account, e.g. {@code HDFC ••••6789}
 * @param verified   only verified accounts may receive a settlement
 * @param holderName the account holder's name, {@code null} for accounts stored before it was kept
 */
public record BankAccountResponse(String id, String masked, boolean verified, String holderName) {

    public static BankAccountResponse from(UUID id, BankAccountView view) {
        return new BankAccountResponse(id.toString(), view.masked(), view.verified(), view.holderName());
    }
}
