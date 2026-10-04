package com.homefix.auth.email;

import java.time.Duration;
import java.util.Locale;

/**
 * The text of every email the Auth Service sends (email-auth Requirement 7). Plain text, no
 * password ever (Requirement 7.2). Kept in one place so a production provider with HTML templates
 * replaces one class.
 */
public final class EmailMessages {

    private EmailMessages() {
    }

    /** Sign-up verification code (Requirement 1.3). */
    public static EmailMessage signupCode(String displayName, String code, Duration ttl) {
        return new EmailMessage("Your HomeFix verification code: " + code, """
                Hi %s,

                Your HomeFix verification code is %s.

                Enter it in the app to finish creating your account. It expires in %d minutes.
                If you did not sign up for HomeFix, you can ignore this email.
                """.formatted(greetingName(displayName), code, ttl.toMinutes()));
    }

    /**
     * Sent instead of a code when someone signs up with an address that already has an account
     * (Requirement 1.4): the address owner learns about it, the requester learns nothing.
     */
    public static EmailMessage alreadyRegistered() {
        return new EmailMessage("You already have a HomeFix account", """
                Hi,

                Someone tried to create a HomeFix account with this email address, but it already
                belongs to an account.

                If it was you, sign in with this email and your password, or use "Forgot password?"
                on the sign-in screen. If it was not you, no action is needed: nothing was changed.
                """);
    }

    /** Password reset code (Requirement 3.1). */
    public static EmailMessage resetCode(String code, Duration ttl) {
        return new EmailMessage("Your HomeFix password reset code: " + code, """
                Hi,

                Your HomeFix password reset code is %s. It expires in %d minutes.

                If you did not ask to reset your password, ignore this email: your password stays
                as it is.
                """.formatted(code, ttl.toMinutes()));
    }

    /** Code confirming an email address added to an existing account (Requirement 4.1). */
    public static EmailMessage emailChangeCode(String code, Duration ttl) {
        return new EmailMessage("Confirm your email for HomeFix: " + code, """
                Hi,

                Enter %s in the HomeFix app to add this email address to your account.
                The code expires in %d minutes. If you did not ask for this, ignore this email.
                """.formatted(code, ttl.toMinutes()));
    }

    /** Staff invitation (Requirement 6.2). The link carries the token; it is never logged. */
    public static EmailMessage invitation(String inviterName, String role, String link, Duration ttl) {
        return new EmailMessage("You're invited to the HomeFix Admin Portal", """
                Hi,

                %s has invited you to join the HomeFix Admin Portal as %s.

                Accept the invitation and set your password here:
                %s

                The link works once and expires in %d days. If you were not expecting this, ignore
                this email.
                """.formatted(inviterName, roleLabel(role), link, ttl.toDays()));
    }

    /** Agency application decision (Requirement 5.7). */
    public static EmailMessage agencyDecision(String tenantName, boolean approved, String reason) {
        if (approved) {
            return new EmailMessage("Your agency " + tenantName + " is approved on HomeFix", """
                    Hi,

                    Good news: %s has been approved on HomeFix.

                    Sign in to the HomeFix Admin Portal again to open your agency's portal, add your
                    team and start taking requests.
                    """.formatted(tenantName));
        }
        return new EmailMessage("Your agency application for " + tenantName, """
                Hi,

                We could not approve %s on HomeFix this time.

                Reason: %s

                You can sign in to the HomeFix Admin Portal to see the details and apply again.
                """.formatted(tenantName, reason == null || reason.isBlank() ? "not given" : reason));
    }

    private static String greetingName(String displayName) {
        return displayName == null || displayName.isBlank() ? "there" : displayName.strip();
    }

    /** {@code FINANCE_ADMIN} reads as "Finance admin". */
    static String roleLabel(String role) {
        String words = role.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
