package com.homefix.auth.password;

import java.time.Duration;

/**
 * Tracks consecutive failed password attempts per username and the lockout that follows.
 *
 * <p>Mirrors the OTP flow's lockout (Requirement 1.3) for the password surface: brute-forcing
 * a six-digit code and brute-forcing a password are the same attack, so they get the same
 * defence. Abstracted behind an interface so the sign-in flow can be unit-tested against an
 * in-memory fake with no Redis dependency.
 */
public interface LoginAttemptStore {

    /** @return true if the username is currently within a lockout window. */
    boolean isLocked(String username);

    /** @return remaining lockout time, or {@link Duration#ZERO} when not locked. */
    Duration lockRemaining(String username);

    /**
     * Records one failed attempt within the supplied window and returns the running total.
     * The first failure in a window starts the expiry clock.
     */
    int recordFailure(String username, Duration window);

    /** Locks the username for the supplied duration and clears its failure counter. */
    void lock(String username, Duration lockout);

    /** Clears the failure counter after a successful sign-in. */
    void clearFailures(String username);

    /**
     * Lifts a lockout and clears the failure counter: the owner has proven control of the account
     * another way (a password reset by emailed code).
     */
    void unlock(String username);
}
