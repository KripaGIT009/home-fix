package com.homefix.auth.emailauth;

/**
 * What an emailed code proves. Codes are stored per purpose, so a sign-up code can never reset a
 * password or confirm an email change.
 */
public enum CodePurpose {

    /** Finishing an email sign-up; keyed by the email address. */
    SIGNUP,

    /** Resetting a forgotten password; keyed by the email address. */
    RESET,

    /** Adding an email to a signed-in account; keyed by the account id, the new address as payload. */
    EMAIL_CHANGE
}
