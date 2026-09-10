/** Auth flow constants (Requirement 1.1–1.5). */

/** OTP length expected by the verify form (6-digit OTP input). */
export const OTP_LENGTH = 6;

/** Default OTP validity window in seconds (5 minutes — Requirement 1.4). */
export const OTP_EXPIRY_SECONDS = 5 * 60;

/**
 * India mobile number: optional +91 country code followed by a 10-digit number
 * beginning 6–9. Kept deliberately simple; the Auth Service is authoritative
 * for delivery (Requirement 1.16).
 */
export const MOBILE_NUMBER_REGEX = /^(?:\+91)?[6-9]\d{9}$/;

/**
 * Error code returned by the Auth Service when an OTP session is locked after
 * too many incorrect attempts (Requirement 1.3).
 */
export const OTP_LOCKED_CODE = 'OTP_SESSION_LOCKED';
