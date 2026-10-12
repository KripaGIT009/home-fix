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
 * Seconds before another emailed code may be requested: the Auth Service
 * allows one resend a minute (email-auth Requirement 1.8).
 */
export const EMAIL_CODE_RESEND_SECONDS = 60;

/** Password rule mirrored from the Auth Service (email-auth Requirement 1.2). */
export const PASSWORD_MIN_LENGTH = 8;
/** bcrypt's limit, counted in UTF-8 bytes by the server. */
export const PASSWORD_MAX_BYTES = 72;

/** Display name length accepted at sign-up. */
export const DISPLAY_NAME_MIN_LENGTH = 2;
export const DISPLAY_NAME_MAX_LENGTH = 80;

/** Auth Service error codes the email flows react to (beyond showing the message). */
export const AUTH_ERROR = {
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  EMAIL_NOT_VERIFIED: 'EMAIL_NOT_VERIFIED',
  WEAK_PASSWORD: 'WEAK_PASSWORD',
  ACCOUNT_LOCKED: 'ACCOUNT_LOCKED',
  ACCOUNT_DISABLED: 'ACCOUNT_DISABLED',
  MOBILE_IN_USE: 'MOBILE_IN_USE',
  INVALID_CODE: 'INVALID_CODE',
  CODE_EXPIRED: 'CODE_EXPIRED',
  TOO_MANY_REQUESTS: 'TOO_MANY_REQUESTS',
  EMAIL_DELIVERY_FAILED: 'EMAIL_DELIVERY_FAILED',
  EMAIL_REQUIRED: 'EMAIL_REQUIRED',
  EMAIL_IN_USE: 'EMAIL_IN_USE',
  CURRENT_PASSWORD_INCORRECT: 'CURRENT_PASSWORD_INCORRECT',
} as const;
