package com.homefix.provider.api.dto;

/**
 * Request body for {@code PUT /providers/{id}/bank-account} (Requirements 4.9, 14.2).
 *
 * <p>Validation happens in the service after normalisation (spaces stripped from the number, IFSC
 * upper-cased), so a number typed in groups is accepted and every field error is reported at once
 * as 400 {@code VALIDATION_ERROR} with one detail per field.
 */
public record BankAccountRequest(String accountHolderName, String accountNumber, String ifsc) {
}
