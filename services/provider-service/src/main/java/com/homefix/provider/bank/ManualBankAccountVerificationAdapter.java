package com.homefix.provider.bank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link BankAccountVerificationPort}: an account is never verified automatically. It stays
 * {@link Outcome#PENDING} until an ADMIN, SUPER_ADMIN or FINANCE_ADMIN marks it verified through
 * {@code POST /admin/providers/{id}/bank-account/verification}.
 */
@Component
@ConditionalOnProperty(name = "homefix.provider.bank-verification", havingValue = "manual",
        matchIfMissing = true)
public class ManualBankAccountVerificationAdapter implements BankAccountVerificationPort {

    @Override
    public Outcome verify(BankAccountDetails details) {
        return Outcome.PENDING;
    }
}
