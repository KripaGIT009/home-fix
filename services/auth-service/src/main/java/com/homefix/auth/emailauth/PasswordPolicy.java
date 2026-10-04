package com.homefix.auth.emailauth;

import java.nio.charset.StandardCharsets;

/**
 * The password rule for every password this feature sets: sign-up, reset, profile change and
 * invitation acceptance (email-auth Requirement 1.2). 8 to 72 characters with at least one letter
 * and one digit. 72 is bcrypt's limit in bytes, so a longer password would be silently truncated;
 * it is counted in UTF-8 bytes for that reason.
 *
 * <p>Sign-in does not apply this rule: tightening it later must not lock out existing passwords.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @throws EmailAuthException 400 {@code WEAK_PASSWORD} naming what is missing */
    public static void requireAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw EmailAuthException.weakPassword("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw EmailAuthException.weakPassword("Use at most " + MAX_BYTES + " characters.");
        }
        boolean letter = password.codePoints().anyMatch(Character::isLetter);
        boolean digit = password.codePoints().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw EmailAuthException.weakPassword("Use at least one letter and one number.");
        }
    }
}
