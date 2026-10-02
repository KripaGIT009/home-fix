package com.homefix.auth.domain;

import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * Raised when an account that is not {@link AccountStatus#ACTIVE} tries to authenticate: OTP
 * verification, password sign-in, social login, or refresh-token rotation.
 *
 * <p>Surfaced as {@code 403 ACCOUNT_DISABLED} rather than a 401: the caller proved who they are,
 * and re-authenticating will not help, so the client should show the message instead of looping
 * back to the sign-in screen. Every path raises it only <em>after</em> the credential itself has
 * been verified (a correct OTP, a matching password, a valid identity token, an unspent refresh
 * token), so the response never tells an unauthenticated caller anything about an account they
 * could not already sign in to.
 */
public class AccountDisabledException extends RuntimeException {

    public static final String ERROR_CODE = "ACCOUNT_DISABLED";

    private final AccountStatus accountStatus;

    public AccountDisabledException(AccountStatus accountStatus) {
        super("This account has been " + accountStatus.name().toLowerCase(Locale.ROOT)
                + ". Contact HomeFix support to restore access.");
        this.accountStatus = accountStatus;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public HttpStatus getStatus() {
        return HttpStatus.FORBIDDEN;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    /**
     * Throws unless the account may authenticate. The one check every sign-in path shares, so
     * the rule ("only ACTIVE") lives in {@link AccountStatus#canAuthenticate()} alone.
     */
    public static void requireActive(UserAccount account) {
        if (!account.getStatus().canAuthenticate()) {
            throw new AccountDisabledException(account.getStatus());
        }
    }
}
