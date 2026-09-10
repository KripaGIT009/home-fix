package com.homefix.auth.otp;

/**
 * Immutable snapshot of a pending OTP session as stored in Redis.
 *
 * @param codeHash    salted hash of the issued OTP (the raw code is never stored)
 * @param attempts    number of incorrect verification attempts so far
 * @param role        the role the account will be granted on successful verification
 */
public record OtpSession(String codeHash, int attempts, String role) {
}
