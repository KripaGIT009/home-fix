package com.homefix.provider.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-only {@link BankAccountVerificationPort}: well-formed details are verified immediately, so
 * a developer can add an account and request a settlement without an administrator. Nothing is
 * checked against a bank. Enabled with {@code homefix.provider.bank-verification=simulator}; never
 * set it in a deployed environment.
 */
@Component
@ConditionalOnProperty(name = "homefix.provider.bank-verification", havingValue = "simulator")
public class SimulatorBankAccountVerificationAdapter implements BankAccountVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatorBankAccountVerificationAdapter.class);

    public SimulatorBankAccountVerificationAdapter() {
        log.warn("Bank account verification is SIMULATED: well-formed accounts are verified without "
                + "any bank check. Local use only.");
    }

    @Override
    public Outcome verify(BankAccountDetails details) {
        return details != null && details.isWellFormed() ? Outcome.VERIFIED : Outcome.PENDING;
    }
}
