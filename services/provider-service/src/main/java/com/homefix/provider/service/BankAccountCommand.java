package com.homefix.provider.service;

/**
 * A provider's settlement bank account as submitted (Requirements 4.9, 14.2), before
 * normalisation: the service strips spaces from the number and upper-cases the IFSC, then
 * validates every field.
 */
public record BankAccountCommand(String accountHolderName, String accountNumber, String ifsc) {
}
