package com.homefix.auth.otp;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage abstraction for OTP sessions, verification-attempt tracking, session lockout,
 * and per-phone request rate limiting.
 *
 * <p>Backed by Redis in production (TTL-based expiry gives the 5-minute OTP window and the
 * 30-minute lockout window for free). Abstracted behind an interface so the OTP flow can be
 * unit-tested against an in-memory fake with no Redis dependency.
 */
public interface OtpStore {

    /**
     * Persists a pending OTP session for the phone with the supplied time-to-live.
     * Overwrites any existing pending session for that phone (a re-request replaces the
     * previous code) and resets the attempt counter.
     */
    void saveSession(String mobileNumber, OtpSession session, Duration ttl);

    /**
     * @return the pending OTP session for the phone, or empty if none exists / it expired.
     */
    Optional<OtpSession> findSession(String mobileNumber);

    /**
     * Records one additional incorrect verification attempt and returns the new total.
     * The counter shares the lifetime of the session.
     */
    int incrementAttempts(String mobileNumber);

    /** Removes the pending session (e.g. after successful verification). */
    void clearSession(String mobileNumber);

    /**
     * Marks the phone's OTP session as locked for the supplied duration. While locked,
     * {@link #isLocked} returns true and no verification should be attempted.
     */
    void lock(String mobileNumber, Duration lockout);

    /** @return true if the phone is currently within a lockout window. */
    boolean isLocked(String mobileNumber);

    /** @return remaining lockout time, or {@link Duration#ZERO} if not locked. */
    Duration lockRemaining(String mobileNumber);

    /**
     * Atomically records one OTP request for the phone within the sliding window and returns
     * the running count for the current window. The first call in a window starts the window
     * with the supplied expiry.
     *
     * @return the number of OTP requests made in the current window, including this one
     */
    long recordRequestAndCount(String mobileNumber, Duration window);
}
