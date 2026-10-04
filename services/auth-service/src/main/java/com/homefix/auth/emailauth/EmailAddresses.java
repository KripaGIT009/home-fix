package com.homefix.auth.emailauth;

import java.util.Locale;

/** Email addresses are compared and stored lower case and trimmed (email-auth Requirement 2.1). */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    public static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Whether a sign-in identifier is an email address rather than a console username. */
    public static boolean looksLikeEmail(String identifier) {
        return identifier != null && identifier.indexOf('@') > 0;
    }
}
