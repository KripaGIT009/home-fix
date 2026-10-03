package com.homefix.provider.bank;

/**
 * What may be shown about a stored bank account: never the number or the ciphertext.
 *
 * @param masked     {@code "<first 4 of IFSC> ••••<last 4 digits>"}, computed from the decrypted
 *                   number; less when the stored value is a legacy reference (see
 *                   {@link BankAccountCodec#describe(String, boolean)})
 * @param verified   only a verified account may receive a settlement
 * @param holderName the account holder, or {@code null} when the stored value does not carry one
 */
public record BankAccountView(String masked, boolean verified, String holderName) {
}
