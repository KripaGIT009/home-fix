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

/**
 * Email sign-up, sign-in and reset (email-auth Requirements 1–4).
 *
 * Codes emailed for sign-up, password reset and an email change are 6 digits,
 * like the OTP, and valid for 10 minutes.
 */
export const EMAIL_CODE_EXPIRY_SECONDS = 10 * 60;

/** The Auth Service sends at most one new code a minute per address (Requirement 1.8). */
export const RESEND_COOLDOWN_SECONDS = 60;

/**
 * The Auth Service's password rule (Requirement 1.2): 8–72 characters with at
 * least one letter and one digit. 72 is bcrypt's limit in UTF-8 bytes, which
 * is how the server counts it.
 */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_BYTES = 72;

/** Display name length accepted by email sign-up. */
export const DISPLAY_NAME_MIN_LENGTH = 2;
export const DISPLAY_NAME_MAX_LENGTH = 80;

/** Longest email address the Auth Service accepts. */
export const EMAIL_MAX_LENGTH = 254;

/** Error codes of the email-auth endpoints the screens react to, not just display. */
export const EMAIL_AUTH_ERRORS = {
  emailNotVerified: 'EMAIL_NOT_VERIFIED',
  accountLocked: 'ACCOUNT_LOCKED',
  accountDisabled: 'ACCOUNT_DISABLED',
  invalidCredentials: 'INVALID_CREDENTIALS',
  mobileInUse: 'MOBILE_IN_USE',
  codeExpired: 'CODE_EXPIRED',
  emailRequired: 'EMAIL_REQUIRED',
  tooManyRequests: 'TOO_MANY_REQUESTS',
} as const;
