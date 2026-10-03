package com.homefix.provider.bank;

import java.util.regex.Pattern;

/**
 * A provider's settlement bank account in clear text (Requirement 4.9): who holds it, its number
 * and the branch's IFSC. Exists only in memory — it is sealed into one encrypted value by
 * {@link BankAccountCodec} before it reaches the database.
 *
 * <p>Values are expected already normalised (number without spaces, IFSC upper-cased); the service
 * does that before building one. {@link #toString()} is masked so an accidental log line never
 * carries the account number.
 */
public record BankAccountDetails(String holderName, String accountNumber, String ifsc) {

    /** Minimum and maximum account-holder name length, after trimming. */
    public static final int HOLDER_NAME_MIN = 2;
    public static final int HOLDER_NAME_MAX = 100;

    /** Indian bank account numbers: digits only, 9–18 long. */
    public static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{9,18}$");

    /** IFSC: four bank letters, a literal zero, six alphanumeric branch characters. */
    public static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    /** {@code true} when every field satisfies the format rules above. */
    public boolean isWellFormed() {
        return holderName != null
                && holderName.strip().length() >= HOLDER_NAME_MIN
                && holderName.strip().length() <= HOLDER_NAME_MAX
                && accountNumber != null && ACCOUNT_NUMBER.matcher(accountNumber).matches()
                && ifsc != null && IFSC.matcher(ifsc).matches();
    }

    @Override
    public String toString() {
        return "BankAccountDetails[" + BankAccountCodec.mask(ifsc, accountNumber) + "]";
    }
}
