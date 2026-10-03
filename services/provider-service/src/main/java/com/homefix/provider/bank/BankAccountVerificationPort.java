package com.homefix.provider.bank;

/**
 * Decides whether a newly added bank account is verified straight away (Requirement 14.2: only a
 * verified account may receive a settlement).
 *
 * <p>Selected by {@code homefix.provider.bank-verification}:
 * <ul>
 *   <li>{@code manual} (default, {@link ManualBankAccountVerificationAdapter}) — every account is
 *       {@link Outcome#PENDING} until a platform or finance administrator marks it verified through
 *       {@code POST /admin/providers/{id}/bank-account/verification};</li>
 *   <li>{@code simulator} (local stacks only, {@link SimulatorBankAccountVerificationAdapter}) —
 *       well-formed details are {@link Outcome#VERIFIED} at once, so settlements can be exercised
 *       end to end without an administrator.</li>
 * </ul>
 * A bank-API adapter (penny drop) can replace either behind the same port.
 */
public interface BankAccountVerificationPort {

    /** The verification state a newly stored account starts in. */
    enum Outcome {
        VERIFIED, PENDING
    }

    /** Runs verification for {@code details}, already validated and normalised. */
    Outcome verify(BankAccountDetails details);
}
